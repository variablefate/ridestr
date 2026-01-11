package com.ridestr.rider

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridestr.rider.ui.screens.DebugScreen
import com.ridestr.rider.ui.screens.RiderModeScreen
import com.ridestr.rider.ui.screens.KeyBackupScreen
import com.ridestr.rider.ui.screens.OnboardingScreen
import com.ridestr.rider.ui.screens.ProfileSetupScreen
import com.ridestr.rider.ui.screens.SettingsScreen
import com.ridestr.common.settings.SettingsManager
import com.ridestr.rider.viewmodels.RiderViewModel
import com.ridestr.rider.viewmodels.OnboardingViewModel
import com.ridestr.rider.viewmodels.ProfileViewModel
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.ui.theme.RidestrTheme

/**
 * App navigation screens.
 */
enum class Screen {
    ONBOARDING,
    PROFILE_SETUP,
    MAIN,
    DEBUG,
    BACKUP_KEYS,
    SETTINGS
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

    // NostrService for relay connections (shared instance)
    val nostrService = remember { NostrService(context) }

    // Settings manager
    val settingsManager = remember { SettingsManager(context) }

    // Navigation state
    var currentScreen by remember { mutableStateOf(Screen.ONBOARDING) }

    // Relay state
    val connectionStates by nostrService.connectionStates.collectAsState()
    val recentEvents by nostrService.relayManager.events.collectAsState()
    val notices by nostrService.relayManager.notices.collectAsState()

    // Check if already logged in on first composition
    LaunchedEffect(uiState.isLoggedIn, uiState.isProfileCompleted) {
        if (uiState.isLoggedIn && currentScreen == Screen.ONBOARDING) {
            currentScreen = if (uiState.isProfileCompleted) {
                Screen.MAIN
            } else {
                Screen.PROFILE_SETUP
            }
            nostrService.connect()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.ONBOARDING -> {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        nostrService.connect()
                        currentScreen = if (uiState.isProfileCompleted) {
                            Screen.MAIN
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
                val useDemoLocation by settingsManager.useDemoLocation.collectAsState()

                DebugScreen(
                    npub = onboardingViewModel.getKeyManager().getNpub(),
                    pubKeyHex = onboardingViewModel.getKeyManager().getPubKeyHex(),
                    connectionStates = connectionStates,
                    recentEvents = recentEvents,
                    notices = notices,
                    useGeocodingSearch = useGeocodingSearch,
                    useDemoLocation = useDemoLocation,
                    onToggleGeocodingSearch = { settingsManager.toggleUseGeocodingSearch() },
                    onToggleDemoLocation = { settingsManager.toggleUseDemoLocation() },
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
