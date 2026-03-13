package com.drivestr.app

import android.Manifest
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.drivestr.app.ui.screens.DriverModeScreen
import com.ridestr.common.LogoutManager
import com.ridestr.common.ui.screens.KeyBackupScreen
import com.drivestr.app.ui.screens.OnboardingScreen
import com.drivestr.app.ui.screens.ProfileSetupScreen
import com.drivestr.app.ui.screens.SettingsContent
import com.drivestr.app.ui.screens.VehiclesScreen
import com.drivestr.app.ui.screens.VehicleSetupScreen
import com.drivestr.app.ui.screens.WalletScreen
import com.drivestr.app.ui.screens.EarningsScreen
import com.drivestr.app.ui.screens.RoadflareTab
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.ui.RideDetailScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.settings.SettingsRepository
import com.ridestr.common.settings.SettingsUiState
import javax.inject.Inject
import com.ridestr.common.ui.AccountBottomSheet
import com.ridestr.common.ui.AccountSafetyScreen
import com.ridestr.common.ui.DeveloperOptionsScreen
import com.ridestr.common.ui.EncryptionFallbackWarningDialog
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
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.sync.DriverRoadflareSyncAdapter
import com.ridestr.common.roadflare.RoadflareKeyManager
import com.ridestr.common.nostr.events.RoadflareFollowNotifyEvent
import com.drivestr.app.service.RoadflareListenerService
import com.ridestr.common.nostr.events.RoadflareKeyAckEvent
import com.drivestr.app.service.DriverOnlineService
import com.ridestr.common.bitcoin.BitcoinPriceService

/**
 * Bottom navigation tabs for the main screen.
 */
enum class Tab {
    DRIVE,      // Main driver mode
    ROADFLARE,  // RoadFlare followers and broadcasting
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
    PROFILE_EDIT,       // Edit existing profile (from account menu)
    VEHICLE_SETUP,      // Vehicle onboarding for drivers without vehicles
    WALLET_SETUP,       // Wallet onboarding (after vehicle, before location)
    LOCATION_PERMISSION,
    TILE_SETUP,
    MAIN,           // Shows bottom navigation with tabs
    WALLET_DETAIL,  // Full wallet interface (deposit, withdraw, etc.)
    WALLET_SETTINGS,// Wallet management settings
    EARNINGS,       // Full earnings history (navigated from wallet)
    RIDE_DETAIL,    // Detail view for single ride
    BACKUP_KEYS,
    TILES,
    DEV_OPTIONS,
    ACCOUNT_SAFETY,
    RELAY_SETTINGS
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val isReady by settingsRepository.isReady.collectAsState()
            LaunchedEffect(Unit) { settingsRepository.awaitInitialLoad() }

            RidestrTheme {
                if (isReady) {
                    DrivestrApp(settingsRepository = settingsRepository)
                }
            }
        }
    }
}

@Composable
fun DrivestrApp(settingsRepository: SettingsRepository) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val driverViewModel: DriverViewModel = viewModel()
    val uiState by onboardingViewModel.uiState.collectAsState()

    // Initialize notification channels once on startup
    LaunchedEffect(Unit) {
        NotificationHelper.createDriverChannels(context)
    }

    // Request battery optimization exemption for reliable background operations
    LaunchedEffect(Unit) {
        val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            // Request exemption - shows system dialog
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Could not request battery optimization exemption: ${e.message}")
            }
        }
    }

    // Settings (read projection for screens)
    val settings by settingsRepository.settings.collectAsState()

    // Notification permission launcher for RoadFlare alerts (Finding 5)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.d("MainActivity", "RoadFlare notification permission result: $granted")
        if (granted) {
            RoadflareListenerService.start(context)
        }
    }

    // NostrService for relay connections (singleton, reads custom relays from settings)
    val nostrService = remember { NostrService.getInstance(context) }

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
            walletKeyManager = walletKeyManager,
            walletStorage = walletService.walletStorage  // For NUT-13 counter backup
        ).also { sync ->
            walletService.setNip60Sync(sync)
        }
    }

    // Auto-connect wallet to saved mint
    LaunchedEffect(Unit) {
        walletService.autoConnect()
    }

    // RoadFlare repository (for sync adapter)
    val driverRoadflareRepo = remember { DriverRoadflareRepository.getInstance(context) }

    // ProfileSyncManager for coordinated profile data sync
    // Registration is synchronous in remember{} to prevent race with PROFILE_SYNC screen
    val profileSyncManager = remember {
        ProfileSyncManager.getInstance(context, settingsRepository.getEffectiveRelays()).also { psm ->
            psm.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
            psm.registerSyncable(ProfileSyncAdapter(
                vehicleRepository = vehicleRepository,
                savedLocationRepository = null,  // Driver app doesn't use saved locations
                settingsRepository = settingsRepository,
                nostrService = nostrService
            ))
            psm.registerSyncable(RideHistorySyncAdapter(rideHistoryRepository, nostrService))
            psm.registerSyncable(DriverRoadflareSyncAdapter(driverRoadflareRepo, nostrService))
        }
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
    val onboardingCompleted = remember { settingsRepository.isOnboardingCompleted() }

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
            // Fetch and cache user's display name for RoadFlare QR code
            nostrService.fetchAndCacheUserDisplayName()
        }
    }

    // Start RoadFlare listener service if enabled (with permission check - Finding 5)
    val roadflareAlertsEnabled by settingsRepository.roadflareAlertsEnabled.collectAsState()
    LaunchedEffect(roadflareAlertsEnabled) {
        if (roadflareAlertsEnabled) {
            if (NotificationHelper.hasNotificationPermission(context)) {
                RoadflareListenerService.start(context)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Request permission - service will start in callback if granted
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Pre-Android 13, permission is implicit
                RoadflareListenerService.start(context)
            }
        } else {
            RoadflareListenerService.stop(context)
        }
    }

    // RoadFlare key manager for handling follower key distribution
    val roadflareKeyManager = remember {
        RoadflareKeyManager(driverRoadflareRepo, nostrService)
    }

    // Subscribe to RoadFlare follow notifications (Kind 3187) and key acks (Kind 3188)
    // Lifecycle-gated: only subscribe when logged in, matching rider-app Kind 3186 pattern
    LaunchedEffect(uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect

        nostrService.keyManager.refreshFromStorage()
        nostrService.ensureConnected()
        nostrService.relayManager.awaitConnected(tag = "roadflareDriverSubs")

        val asyncScope = this

        val subId = nostrService.subscribeToRoadflareFollowNotifications { event, relayUrl ->
            // Check if we should ignore notifications (dev setting for testing p-tag queries)
            if (settingsRepository.getIgnoreFollowNotifications()) {
                android.util.Log.d("MainActivity", "Ignoring RoadFlare follow notification (dev setting enabled)")
                return@subscribeToRoadflareFollowNotifications
            }

            android.util.Log.d("MainActivity", "Received RoadFlare follow notification from ${event.pubKey.take(16)}")

            asyncScope.launch {
                try {
                    val signer = nostrService.keyManager.getSigner() ?: return@launch

                    val notification = RoadflareFollowNotifyEvent.parseAndDecrypt(signer, event)

                    if (notification != null) {
                        when (notification.action) {
                            "follow" -> {
                                // Check if already a follower
                                val existingFollowers = driverRoadflareRepo.state.value?.followers ?: emptyList()
                                if (existingFollowers.none { it.pubkey == notification.riderPubKey }) {
                                    android.util.Log.d("MainActivity", "New RoadFlare follow request from: ${notification.riderName} (${notification.riderPubKey.take(16)})")

                                    // Add as pending follower - driver must approve in RoadflareTab
                                    roadflareKeyManager.handleNewFollower(notification.riderPubKey, notification.riderName)

                                    // Backup updated state to Nostr
                                    profileSyncManager.backupProfileData()
                                }
                            }
                            "unfollow" -> {
                                // Rider removed this driver from their favorites
                                android.util.Log.d("MainActivity", "RoadFlare unfollow from: ${notification.riderName} (${notification.riderPubKey.take(16)})")

                                // Remove follower from our list (no key rotation needed since they're voluntarily leaving)
                                roadflareKeyManager.handleUnfollow(notification.riderPubKey)

                                // Backup updated state to Nostr
                                profileSyncManager.backupProfileData()
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error processing follow notification", e)
                }
            }
        }

        android.util.Log.d("MainActivity", "Subscribed to RoadFlare follow notifications: $subId")

        // Subscribe to RoadFlare key acknowledgements (Kind 3188)
        // When a rider receives and stores our key, they send back an ack
        // Also handles key refresh requests (status="stale")
        val ackSubId = nostrService.subscribeToRoadflareKeyAcks { event, relayUrl ->
            android.util.Log.d("MainActivity", "Received RoadFlare key ack from ${event.pubKey.take(16)}")

            asyncScope.launch {
                try {
                    val signer = nostrService.keyManager.getSigner() ?: return@launch
                    val ackData = RoadflareKeyAckEvent.parseAndDecrypt(signer, event)
                    if (ackData != null) {
                        android.util.Log.d("MainActivity", "Key ack: rider=${ackData.riderPubKey.take(16)} version=${ackData.keyVersion} status=${ackData.status}")

                        // Security: Verify authorship - claimed pubkey matches event signer
                        val pubkeyMatches = ackData.riderPubKey == event.pubKey
                        if (!pubkeyMatches) {
                            android.util.Log.w("MainActivity", "Key ack pubkey mismatch - ignoring (claimed=${ackData.riderPubKey.take(8)}, signer=${event.pubKey.take(8)})")
                        } else {
                            // Verify authorized follower (approved + not muted)
                            val follower = driverRoadflareRepo.getFollowers().find { it.pubkey == ackData.riderPubKey }
                            val isMuted = driverRoadflareRepo.getMutedPubkeys().contains(ackData.riderPubKey)
                            val isAuthorized = follower != null && follower.approved && !isMuted

                            if (!isAuthorized) {
                                android.util.Log.w("MainActivity", "Key ack from unauthorized follower - ignoring")
                            } else {
                                val currentKeyUpdatedAt = driverRoadflareRepo.getKeyUpdatedAt() ?: 0L

                                // Check if re-send needed: status != "received" OR ack has stale timestamp
                                if (ackData.status != "received" || ackData.keyUpdatedAt < currentKeyUpdatedAt) {
                                    android.util.Log.d("MainActivity", "Re-sending key to ${ackData.riderPubKey.take(8)} (status=${ackData.status}, ackKeyUpdatedAt=${ackData.keyUpdatedAt}, currentKeyUpdatedAt=$currentKeyUpdatedAt)")

                                    val currentKey = driverRoadflareRepo.state.value?.roadflareKey
                                    if (currentKey != null) {
                                        val success = roadflareKeyManager.sendKeyToFollower(
                                            signer = signer,
                                            followerPubkey = ackData.riderPubKey,
                                            key = currentKey,
                                            keyUpdatedAt = currentKeyUpdatedAt
                                        )
                                        if (success) {
                                            android.util.Log.d("MainActivity", "Re-sent key v${currentKey.version} to ${ackData.riderPubKey.take(8)}")
                                            driverRoadflareRepo.markFollowerKeySent(ackData.riderPubKey, currentKey.version)
                                        } else {
                                            android.util.Log.e("MainActivity", "Failed to re-send key to ${ackData.riderPubKey.take(8)}")
                                        }
                                    } else {
                                        android.util.Log.w("MainActivity", "No current key to re-send")
                                    }
                                } else if (ackData.keyVersion == follower.keyVersionSent) {
                                    android.util.Log.d("MainActivity", "Follower ${ackData.riderPubKey.take(16)} confirmed key v${ackData.keyVersion}")
                                    // Key version matches - follower has confirmed receipt
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Error processing key ack", e)
                }
            }
        }
        android.util.Log.d("MainActivity", "Subscribed to RoadFlare key acks: $ackSubId")

        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            subId?.let { nostrService.closeSubscription(it) }
            ackSubId?.let { nostrService.closeSubscription(it) }
            android.util.Log.d("MainActivity", "Closed RoadFlare subscriptions: follow=$subId, ack=$ackSubId")
        }
    }

    // Note: Profile sync is now handled by the PROFILE_SYNC screen during onboarding,
    // not by a background LaunchedEffect. This ensures the sync completes before
    // the user sees onboarding screens that depend on synced data (like vehicles).

    // Auto-backup vehicles to Nostr when they change (with debounce)
    val vehicles by vehicleRepository.vehicles.collectAsState()
    var lastVehicleBackupHash by remember { mutableStateOf(emptyList<Any>().hashCode()) }
    LaunchedEffect(vehicles, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect

        // Use hash to detect actual changes (not just recomposition)
        val currentHash = vehicles.hashCode()
        if (currentHash == lastVehicleBackupHash) return@LaunchedEffect

        // Debounce: wait 2 seconds after last change before backing up
        kotlinx.coroutines.delay(2000)

        // Check hash again after debounce (in case more changes happened)
        if (vehicles.hashCode() == currentHash) {
            lastVehicleBackupHash = currentHash
            android.util.Log.d("MainActivity", "Auto-backing up vehicles to Nostr...")
            profileSyncManager.backupProfileData(force = true)
        }
    }

    // Auto-backup settings to Nostr when they change (with debounce)
    val settingsHash by settingsRepository.syncableSettingsHash.collectAsState(initial = 0)
    var lastSettingsBackupHash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settingsHash, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect
        if (settingsHash == 0) return@LaunchedEffect
        if (lastSettingsBackupHash == null) {
            lastSettingsBackupHash = settingsHash
            return@LaunchedEffect
        }
        if (settingsHash == lastSettingsBackupHash) return@LaunchedEffect
        // Debounce: wait 2 seconds before backing up
        kotlinx.coroutines.delay(2000)
        // After debounce, check if settingsHash changed (would trigger new LaunchedEffect)
        // If we get here and weren't cancelled, proceed with backup
        lastSettingsBackupHash = settingsHash
        android.util.Log.d("MainActivity", "Auto-backing up settings to Nostr...")
        profileSyncManager.backupProfileData(force = true)
    }

    val customRelays by settingsRepository.customRelays.collectAsState()
    LaunchedEffect(customRelays, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect
        val effectiveRelays = settingsRepository.getEffectiveRelays()
        profileSyncManager.updateRelays(effectiveRelays)
        val currentNostr = nostrService.relayManager.getRelayUrls().toSet()
        val target = effectiveRelays.toSet()
        (currentNostr - target).forEach { nostrService.relayManager.removeRelay(it) }
        (target - currentNostr).forEach { nostrService.relayManager.addRelay(it) }
        if (currentNostr != target) {
            nostrService.relayManager.ensureConnected()
            driverViewModel.refreshSubscriptions()
        }
    }

    // Check if already logged in on first composition (only if not already at MAIN)
    // IMPORTANT: Don't navigate if showBackupReminder is true - let OnboardingScreen handle that
    LaunchedEffect(uiState.isLoggedIn, uiState.isProfileCompleted, uiState.showBackupReminder, uiState.needsProfileSync) {
        if (uiState.isLoggedIn && !uiState.showBackupReminder && currentScreen == Screen.ONBOARDING) {
            nostrService.connect()
            nostrService.fetchAndCacheUserDisplayName()

            // Determine next screen based on completion status
            currentScreen = when {
                // If profile sync needed, go to sync screen
                uiState.needsProfileSync -> Screen.PROFILE_SYNC
                // If onboarding fully completed, go to main
                onboardingCompleted -> Screen.MAIN
                // If profile not complete, go to profile setup
                !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                // If wallet setup not done, go to wallet setup
                !settingsRepository.isWalletSetupDone() -> Screen.WALLET_SETUP
                // If no location permission, request it
                !hasLocationPermission -> Screen.LOCATION_PERMISSION
                // Skip tile setup if already have tiles downloaded
                hasTilesDownloaded.isNotEmpty() -> {
                    settingsRepository.setOnboardingCompleted(true)
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
                        nostrService.keyManager.refreshFromStorage()
                        profileSyncManager.keyManager.refreshFromStorage()

                        val hasVehicles = vehicleRepository.vehicles.value.isNotEmpty()
                        currentScreen = when {
                            uiState.needsProfileSync -> Screen.PROFILE_SYNC
                            onboardingCompleted -> Screen.MAIN
                            !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsRepository.isWalletSetupDone() -> Screen.WALLET_SETUP
                            !hasLocationPermission -> Screen.LOCATION_PERMISSION
                            hasTilesDownloaded.isNotEmpty() -> {
                                coroutineScope.launch { settingsRepository.setOnboardingCompleted(true) }
                                Screen.MAIN
                            }
                            else -> Screen.TILE_SETUP
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

                val syncRetryScope = rememberCoroutineScope()

                ProfileSyncScreen(
                    syncState = syncState,
                    isDriverApp = true,
                    onComplete = { restoredData ->
                        profileSyncManager.resetSyncState()
                        onboardingViewModel.markProfileSyncCompleted()
                        // After sync, check what was restored and navigate appropriately
                        val hasVehicles = restoredData.vehicleCount > 0 ||
                                vehicleRepository.vehicles.value.isNotEmpty()

                        currentScreen = when {
                            !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsRepository.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    onSkip = {
                        profileSyncManager.resetSyncState()
                        onboardingViewModel.markProfileSyncCompleted()
                        // Skip sync, proceed with normal onboarding
                        val hasVehicles = vehicleRepository.vehicles.value.isNotEmpty()
                        currentScreen = when {
                            !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsRepository.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    onRetry = {
                        syncRetryScope.launch { profileSyncManager.checkAndSyncRidestrData() }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.PROFILE_SETUP -> {
                val profileViewModel: ProfileViewModel = viewModel()
                val hasVehicles = vehicleRepository.vehicles.collectAsState().value.isNotEmpty()

                ProfileSetupScreen(
                    viewModel = profileViewModel,
                    canSkip = uiState.needsProfileSync,
                    onComplete = {
                        // Start tile discovery early (before location permission)
                        tileDiscoveryService.startDiscovery()
                        // After profile, check if driver has vehicles
                        currentScreen = when {
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsRepository.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.PROFILE_EDIT -> {
                val profileViewModel: ProfileViewModel = viewModel()
                ProfileSetupScreen(
                    viewModel = profileViewModel,
                    onComplete = {
                        currentScreen = Screen.MAIN
                    },
                    isEditMode = true,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.VEHICLE_SETUP -> {
                VehicleSetupScreen(
                    vehicleRepository = vehicleRepository,
                    onComplete = {
                        // After adding vehicle, continue to wallet setup
                        currentScreen = if (settingsRepository.isWalletSetupDone()) {
                            Screen.LOCATION_PERMISSION
                        } else {
                            Screen.WALLET_SETUP
                        }
                    },
                    onSkip = {
                        // Allow skipping vehicle setup (can add later)
                        currentScreen = if (settingsRepository.isWalletSetupDone()) {
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
                        coroutineScope.launch { settingsRepository.setWalletSetupCompleted(true) }
                        currentScreen = Screen.LOCATION_PERMISSION
                    },
                    onSkip = {
                        coroutineScope.launch { settingsRepository.setWalletSetupSkipped(true) }
                        currentScreen = Screen.LOCATION_PERMISSION
                    },
                    modifier = Modifier.padding(innerPadding),
                    subtitle = "Needed to receive payments"
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
                            coroutineScope.launch { settingsRepository.setOnboardingCompleted(true) }
                            currentScreen = Screen.MAIN
                        } else {
                            currentScreen = Screen.TILE_SETUP
                        }
                    },
                    onSkip = {
                        // Skip tile setup if tiles already downloaded
                        if (hasTilesDownloaded.isNotEmpty()) {
                            coroutineScope.launch { settingsRepository.setOnboardingCompleted(true) }
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
                        coroutineScope.launch { settingsRepository.setOnboardingCompleted(true) }
                        currentScreen = Screen.MAIN
                    },
                    onSkip = {
                        // Allow skipping tile setup
                        coroutineScope.launch { settingsRepository.setOnboardingCompleted(true) }
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
                    settingsRepository = settingsRepository,
                    settings = settings,
                    nostrService = nostrService,
                    vehicleRepository = vehicleRepository,
                    rideHistoryRepository = rideHistoryRepository,
                    driverRoadflareRepository = driverRoadflareRepo,
                    roadflareKeyManager = roadflareKeyManager,
                    profileSyncManager = profileSyncManager,
                    walletService = walletService,
                    onLogout = {
                        driverViewModel.performLogoutCleanup()
                        LogoutManager.performFullCleanup(
                            context = context,
                            nostrService = nostrService,
                            settingsRepository = settingsRepository,
                            walletService = walletService,
                            walletKeyManager = walletKeyManager,
                            tileDiscoveryService = tileDiscoveryService
                        )
                        onboardingViewModel.logout()
                        currentScreen = Screen.ONBOARDING
                    },
                    onOpenProfile = {
                        currentScreen = Screen.PROFILE_EDIT
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
                        displayCurrency = settings.displayCurrency,
                        onToggleCurrency = { coroutineScope.launch { settingsRepository.toggleDisplayCurrency() } },
                        favoriteLnAddresses = settings.favoriteLnAddresses,
                        onAddFavoriteLnAddress = { addr -> coroutineScope.launch { settingsRepository.addFavoriteLnAddress(addr) } },
                        onUpdateFavoriteLastUsed = { addr -> coroutineScope.launch { settingsRepository.updateFavoriteLastUsed(addr) } },
                        priceService = bitcoinPriceService,
                        onBack = { currentScreen = Screen.MAIN },
                        modifier = Modifier.padding(innerPadding)
                    )
                } else {
                    // Fallback if wallet not set up
                    currentScreen = Screen.WALLET_SETUP
                }
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
                val driverViewModel: DriverViewModel = viewModel()
                DeveloperOptionsScreen(
                    useGeocodingSearch = settings.useGeocodingSearch,
                    onToggleUseGeocodingSearch = { coroutineScope.launch { settingsRepository.toggleUseGeocodingSearch() } },
                    ignoreFollowNotifications = settings.ignoreFollowNotifications,
                    onSetIgnoreFollowNotifications = { enabled -> coroutineScope.launch { settingsRepository.setIgnoreFollowNotifications(enabled) } },
                    useManualDriverLocation = settings.useManualDriverLocation,
                    onSetUseManualDriverLocation = { enabled -> coroutineScope.launch { settingsRepository.setUseManualDriverLocation(enabled) } },
                    manualDriverLat = settings.manualDriverLat,
                    manualDriverLon = settings.manualDriverLon,
                    onSetManualDriverLocation = { lat, lon -> coroutineScope.launch { settingsRepository.setManualDriverLocation(lat, lon) } },
                    isDriverApp = true,
                    nostrService = nostrService,
                    onOpenRelaySettings = { currentScreen = Screen.RELAY_SETTINGS },
                    onBack = { currentScreen = Screen.MAIN },
                    // RoadFlare key debug callbacks
                    onGetLocalKeyVersion = { driverRoadflareRepo.getKeyVersion() },
                    onGetLocalKeyUpdatedAt = { driverRoadflareRepo.getKeyUpdatedAt() },
                    onFetchNostrKeyUpdatedAt = {
                        val myPubKey = nostrService.keyManager.getPubKeyHex()
                        if (myPubKey != null) nostrService.fetchDriverKeyUpdatedAt(myPubKey) else null
                    },
                    onSyncRoadflareState = { driverViewModel.syncRoadflareState() },
                    onRotateRoadflareKey = {
                        val signer = nostrService.getSigner()
                        if (signer != null) roadflareKeyManager.rotateKey(signer) else false
                    },
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
                    relays = settingsRepository.getEffectiveRelays(),
                    isUsingCustomRelays = settings.isUsingCustomRelays,
                    maxRelays = SettingsRepository.MAX_RELAYS,
                    onAddRelay = { url -> coroutineScope.launch { settingsRepository.addRelay(url) } },
                    onRemoveRelay = { url -> coroutineScope.launch { settingsRepository.removeRelay(url) } },
                    onResetRelays = { coroutineScope.launch { settingsRepository.resetRelaysToDefault() } },
                    connectedCount = connectedCount,
                    totalRelays = totalRelays,
                    connectionStates = connectionStates,
                    onBack = { currentScreen = Screen.MAIN },
                    onReconnect = {
                        nostrService.relayManager.disconnectAll()
                        nostrService.relayManager.connectAll()
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.WALLET_SETTINGS -> {
                val driverViewModel: DriverViewModel = viewModel()
                val remoteConfig by driverViewModel.remoteConfig.collectAsState()
                WalletSettingsScreen(
                    walletService = walletService,
                    alwaysShowWalletDiagnostics = settings.alwaysShowWalletDiagnostics,
                    onSetAlwaysShowWalletDiagnostics = { enabled -> coroutineScope.launch { settingsRepository.setAlwaysShowWalletDiagnostics(enabled) } },
                    isDriverApp = true,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding),
                    recommendedMints = remoteConfig.recommendedMints
                )
            }

            Screen.EARNINGS -> {
                EarningsScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = { coroutineScope.launch { settingsRepository.toggleDisplayCurrency() } },
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
                val btcPrice by bitcoinPriceService.btcPriceUsd.collectAsState()

                selectedRide?.let { ride ->
                    RideDetailScreen(
                        ride = ride,
                        displayCurrency = settings.displayCurrency,
                        btcPriceUsd = btcPrice,
                        onToggleCurrency = { coroutineScope.launch { settingsRepository.toggleDisplayCurrency() } },
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
    settingsRepository: SettingsRepository,
    settings: SettingsUiState,
    nostrService: NostrService,
    vehicleRepository: VehicleRepository,
    rideHistoryRepository: RideHistoryRepository,
    driverRoadflareRepository: DriverRoadflareRepository,
    roadflareKeyManager: com.ridestr.common.roadflare.RoadflareKeyManager,
    profileSyncManager: ProfileSyncManager,
    walletService: WalletService?,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAccountSafety: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
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

    // Coroutine scope for MainScreen-level async operations
    val mainScreenScope = rememberCoroutineScope()

    // Encryption fallback warning state
    var showEncryptionWarning by remember { mutableStateOf(false) }

    // Check for encryption fallback on startup
    LaunchedEffect(keyManager, walletService) {
        val anyUnencrypted = keyManager.isUsingUnencryptedStorage()
            || walletService?.isUsingUnencryptedStorage() == true
        showEncryptionWarning = anyUnencrypted
            && !settingsRepository.getEncryptionFallbackWarned()
    }

    // Vehicle repository state
    val vehicles by vehicleRepository.vehicles.collectAsState()

    // Driver ViewModel (persists across tab switches)
    val driverViewModel: DriverViewModel = viewModel()
    val driverUiState by driverViewModel.uiState.collectAsState()

    // Wire up wallet service for HTLC escrow settlement
    LaunchedEffect(walletService) {
        driverViewModel.setWalletService(walletService)
    }

    val context = LocalContext.current

    // Bitcoin price for fare display
    val btcPriceUsd by driverViewModel.bitcoinPriceService.btcPriceUsd.collectAsState()

    // Ensure relay connections when app returns to foreground
    // Also clear any stacked notification alerts and RoadFlare request notifications
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        driverViewModel.onResume()
        DriverOnlineService.clearAlerts(context)
        // Cancel RoadFlare request notification when app foregrounds (Finding 4)
        NotificationHelper.cancelNotification(context, RoadflareListenerService.NOTIFICATION_ID_ROADFLARE_REQUEST)
    }

    // Extracted refresh logic - used by both UI refresh button and background sync
    suspend fun refreshRoadflareFollowers(verifiedFollowers: Set<String>? = null) = withContext(Dispatchers.IO) {
        val driverPubkey = keyManager.getPubKeyHex() ?: return@withContext

        // Guard: skip if not connected
        if (!nostrService.isConnected()) {
            android.util.Log.d("RoadflareRefresh", "Skipping refresh - not connected")
            return@withContext
        }

        val foundFollowers = if (verifiedFollowers != null) {
            // Use pre-verified followers from sync, skip redundant query
            if (BuildConfig.DEBUG) {
                android.util.Log.d("RoadflareRefresh", "Using ${verifiedFollowers.size} pre-verified followers, skipping query")
            }
            verifiedFollowers.toMutableSet()
        } else {
            // Manual refresh or no pre-verified data - do full query
            val followers = mutableSetOf<String>()
            val subscriptionId = nostrService.queryRoadflareFollowers(driverPubkey) { riderPubKey ->
                followers.add(riderPubKey)
            }
            kotlinx.coroutines.delay(3000)
            nostrService.closeSubscription(subscriptionId)
            followers
        }

        val existingFollowers = driverRoadflareRepository.getFollowers()
        val existingPubkeys = existingFollowers.map { it.pubkey }.toSet()
        var stateChanged = false

        // Add new followers
        for (pubkey in foundFollowers) {
            if (pubkey !in existingPubkeys) {
                driverRoadflareRepository.addFollower(
                    com.ridestr.common.nostr.events.RoadflareFollower(
                        pubkey = pubkey,
                        name = "",
                        addedAt = System.currentTimeMillis() / 1000,
                        approved = false,
                        keyVersionSent = 0
                    )
                )
                stateChanged = true
            }
        }

        // Verify existing followers not found in p-tag query
        val followersToVerify = existingFollowers.filter { it.pubkey !in foundFollowers }
        for (follower in followersToVerify) {
            val verification = nostrService.verifyFollowerStatus(
                followerPubKey = follower.pubkey,
                driverPubKey = driverPubkey,
                currentKeyUpdatedAt = driverRoadflareRepository.getKeyUpdatedAt()
            )
            if (verification != null && !verification.stillFollowing) {
                driverRoadflareRepository.removeFollower(follower.pubkey)
                driverRoadflareRepository.unmuteRider(follower.pubkey)  // Also clear from muted list
                stateChanged = true
            }
        }

        // Fetch profiles for followers missing display names
        val pubkeysNeedingNames = mutableSetOf<String>()
        for (pubkey in foundFollowers) {
            if (pubkey !in existingPubkeys) pubkeysNeedingNames.add(pubkey)
        }
        for (follower in existingFollowers) {
            if (follower.name.isBlank()) pubkeysNeedingNames.add(follower.pubkey)
        }
        for (pubkey in pubkeysNeedingNames) {
            nostrService.subscribeToProfile(pubkey) { profile ->
                val fullName = profile.displayName ?: profile.name
                val firstName = fullName?.split(" ")?.firstOrNull()
                if (!firstName.isNullOrBlank()) {
                    driverRoadflareRepository.updateFollowerName(pubkey, firstName)
                }
            }
        }

        android.util.Log.d("RoadflareRefresh", "Refresh complete: found=${foundFollowers.size}, stateChanged=$stateChanged")
        if (stateChanged) {
            profileSyncManager.backupProfileData()
        }
    }

    // Combined refresh: sync Kind 30012 state first, then query followers
    // This enables cross-device approval sync (e.g., approve on Phone A, pull-to-refresh on Phone B)
    suspend fun refreshRoadflareStateAndFollowers() {
        // Early exit if offline (avoids 15s wait in fetchDriverRoadflareState)
        if (!nostrService.isConnected()) {
            android.util.Log.d("RoadflareRefresh", "Skipping state sync - not connected")
            return
        }
        // Sync driver's own state (cross-device approval sync via union merge)
        driverViewModel.syncRoadflareState()
        // Then refresh follower list (Kind 30011 p-tag query)
        refreshRoadflareFollowers()
    }

    // Observe sync-triggered refresh from DriverViewModel
    LaunchedEffect(driverViewModel) {
        driverViewModel.syncTriggeredRefresh.collect { verifiedFollowers ->
            android.util.Log.d("MainActivity", "Sync triggered background refresh (${verifiedFollowers?.let { "${it.size} cached" } ?: "full query"})")
            refreshRoadflareFollowers(verifiedFollowers)
        }
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
                            Tab.ROADFLARE -> "RoadFlare"
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
                    icon = { Icon(Icons.Default.Flare, contentDescription = null) },
                    label = { Text("RoadFlare") },
                    selected = currentTab == Tab.ROADFLARE,
                    onClick = { currentTab = Tab.ROADFLARE }
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
                    settings = settings,
                    vehicleRepository = vehicleRepository,
                    autoOpenNavigation = settings.autoOpenNavigation,
                    onToggleCurrency = { driverViewModel.onToggleDisplayCurrency() },
                    onSetActiveVehicleId = { driverViewModel.onSetActiveVehicleId(it) },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.ROADFLARE -> {
                val driverPubkey = keyManager.getPubKeyHex() ?: ""
                val driverNpub = keyManager.getNpub() ?: ""
                val scope = rememberCoroutineScope()

                // Get driver's display name from cached profile
                val driverDisplayName by nostrService.userDisplayName.collectAsState()

                // Check if driver is online for RoadFlare purposes (AVAILABLE or ROADFLARE_ONLY)
                val isDriverOnline = driverUiState.stage == com.drivestr.app.presence.DriverStage.AVAILABLE ||
                                     driverUiState.stage == com.drivestr.app.presence.DriverStage.ROADFLARE_ONLY

                RoadflareTab(
                    driverRoadflareRepository = driverRoadflareRepository,
                    driverPubkey = driverPubkey,
                    driverNpub = driverNpub,
                    driverName = driverDisplayName,
                    roadflarePaymentMethods = settings.roadflarePaymentMethods,
                    onSetRoadflarePaymentMethods = { driverViewModel.onSetRoadflarePaymentMethods(it) },
                    backgroundAlertsEnabled = settings.roadflareAlertsEnabled,
                    isDriverOnline = isDriverOnline,
                    onApproveFollower = { pubkey ->
                        scope.launch {
                            val signer = nostrService.keyManager.getSigner()
                            if (signer != null) {
                                roadflareKeyManager.approveFollower(signer, pubkey)
                                profileSyncManager.backupProfileData()
                            }
                        }
                    },
                    onDeclineFollower = { pubkey ->
                        roadflareKeyManager.declineFollower(pubkey)
                        scope.launch {
                            profileSyncManager.backupProfileData()
                        }
                    },
                    onRemoveFollower = { pubkey ->
                        // "Remove" mutes under the hood — local ignore + key rotation.
                        // Recoverable via Settings > Removed Followers.
                        scope.launch {
                            val signer = nostrService.keyManager.getSigner()
                            if (signer != null) {
                                roadflareKeyManager.handleMuteFollower(
                                    signer = signer,
                                    followerPubkey = pubkey,
                                    reason = "removed by driver"
                                )
                                profileSyncManager.backupProfileData()
                            }
                        }
                    },
                    onRefreshFollowers = {
                        refreshRoadflareStateAndFollowers()
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.WALLET -> {
                WalletScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = { driverViewModel.onToggleDisplayCurrency() },
                    priceService = driverViewModel.bitcoinPriceService,
                    walletSetupCompleted = settings.walletSetupCompleted,
                    walletSetupSkipped = settings.walletSetupSkipped,
                    alwaysShowWalletDiagnostics = settings.alwaysShowWalletDiagnostics,
                    walletService = walletService,
                    onSetupWallet = onSetupWallet,
                    onOpenWalletDetail = onOpenWalletDetail,
                    onViewEarningsDetails = onOpenEarnings,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.VEHICLES -> {
                VehiclesScreen(
                    vehicles = vehicles,
                    alwaysAskVehicle = settings.alwaysAskVehicle,
                    activeVehicleId = settings.activeVehicleId,
                    driverStage = driverUiState.stage,
                    onAddVehicle = { vehicleRepository.addVehicle(it) },
                    onUpdateVehicle = { vehicleRepository.updateVehicle(it) },
                    onDeleteVehicle = { vehicleRepository.deleteVehicle(it) },
                    onSetPrimary = {
                        vehicleRepository.setPrimaryVehicle(it)
                        driverViewModel.onSetActiveVehicleId(it)  // Keep activeVehicleId in sync
                    },
                    onRefresh = onRefreshVehicles,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.SETTINGS -> {
                val roadflareState by driverRoadflareRepository.state.collectAsState()
                val settingsScope = rememberCoroutineScope()
                SettingsContent(
                    settings = settings,
                    onToggleDisplayCurrency = { driverViewModel.onToggleDisplayCurrency() },
                    onToggleDistanceUnit = { driverViewModel.onToggleDistanceUnit() },
                    onSetAutoOpenNavigation = { driverViewModel.onSetAutoOpenNavigation(it) },
                    onSetNotificationSoundEnabled = { driverViewModel.onSetNotificationSoundEnabled(it) },
                    onSetNotificationVibrationEnabled = { driverViewModel.onSetNotificationVibrationEnabled(it) },
                    onSetAlwaysAskVehicle = { driverViewModel.onSetAlwaysAskVehicle(it) },
                    onSetRoadflareAlertsEnabled = { driverViewModel.onSetRoadflareAlertsEnabled(it) },
                    hasMultipleVehicles = vehicles.size > 1,
                    driverStage = driverUiState.stage,
                    onOpenTiles = onOpenTiles,
                    onOpenDevOptions = onOpenDevOptions,
                    onOpenWalletSettings = onOpenWalletSettings,
                    removedFollowers = roadflareState?.muted ?: emptyList(),
                    onUnremoveFollower = { pubkey ->
                        settingsScope.launch {
                            driverRoadflareRepository.unmuteRider(pubkey)
                            profileSyncManager.backupProfileData()
                        }
                    },
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

    // Encryption fallback warning dialog (non-cancelable)
    if (showEncryptionWarning) {
        EncryptionFallbackWarningDialog(
            onDismiss = {
                mainScreenScope.launch { settingsRepository.setEncryptionFallbackWarned(true) }
                showEncryptionWarning = false
            }
        )
    }
}
