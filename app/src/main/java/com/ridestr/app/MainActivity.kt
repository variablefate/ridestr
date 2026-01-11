package com.ridestr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridestr.app.nostr.NostrService
import com.ridestr.app.nostr.keys.SecureKeyStorage
import com.ridestr.app.nostr.relay.RelayConnectionState
import com.ridestr.app.ui.screens.DebugScreen
import com.ridestr.app.ui.screens.DriverModeScreen
import com.ridestr.app.ui.screens.KeyBackupScreen
import com.ridestr.app.ui.screens.OnboardingScreen
import com.ridestr.app.ui.screens.ProfileSetupScreen
import com.ridestr.app.ui.screens.RiderModeScreen
import com.ridestr.app.ui.theme.RidestrTheme
import com.ridestr.app.viewmodels.DriverViewModel
import com.ridestr.app.viewmodels.OnboardingViewModel
import com.ridestr.app.viewmodels.ProfileViewModel
import com.ridestr.app.viewmodels.RiderViewModel
import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * App navigation screens.
 */
enum class Screen {
    ONBOARDING,
    PROFILE_SETUP,
    MAIN,
    DEBUG,
    BACKUP_KEYS
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

    // Navigation state
    var currentScreen by remember { mutableStateOf(Screen.ONBOARDING) }

    // Relay state
    val connectionStates by nostrService.connectionStates.collectAsState()
    val recentEvents by nostrService.relayManager.events.collectAsState()
    val notices by nostrService.relayManager.notices.collectAsState()

    // Check if already logged in on first composition
    // Use uiState.isLoggedIn as key so this runs when the state is available
    LaunchedEffect(uiState.isLoggedIn, uiState.isProfileCompleted) {
        if (uiState.isLoggedIn && currentScreen == Screen.ONBOARDING) {
            // Navigate based on profile completion status
            currentScreen = if (uiState.isProfileCompleted) {
                Screen.MAIN
            } else {
                Screen.PROFILE_SETUP
            }
            // Auto-connect to relays when already logged in
            nostrService.connect()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.ONBOARDING -> {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        // Connect to relays
                        nostrService.connect()
                        // Navigate based on profile completion status
                        // - New keys: go to profile setup
                        // - Imported keys: go to main (profile already exists)
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
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.DEBUG -> {
                DebugScreen(
                    npub = onboardingViewModel.getKeyManager().getNpub(),
                    pubKeyHex = onboardingViewModel.getKeyManager().getPubKeyHex(),
                    connectionStates = connectionStates,
                    recentEvents = recentEvents,
                    notices = notices,
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
        }
    }
}

@Composable
fun MainScreen(
    keyManager: com.ridestr.app.nostr.keys.KeyManager,
    connectionStates: Map<String, RelayConnectionState>,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Get persisted user mode
    var currentMode by remember { mutableStateOf(keyManager.getUserMode()) }

    // Calculate connected relays
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
                            text = keyManager.getNpub()?.take(16) + "..." ?: "Unknown",
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

        // Mode content
        when (currentMode) {
            SecureKeyStorage.UserMode.RIDER -> {
                val riderViewModel: RiderViewModel = viewModel()
                RiderModeScreen(
                    viewModel = riderViewModel,
                    onSwitchToDriver = {
                        keyManager.setUserMode(SecureKeyStorage.UserMode.DRIVER)
                        currentMode = SecureKeyStorage.UserMode.DRIVER
                    },
                    modifier = Modifier.weight(1f)
                )
            }
            SecureKeyStorage.UserMode.DRIVER -> {
                val driverViewModel: DriverViewModel = viewModel()
                DriverModeScreen(
                    viewModel = driverViewModel,
                    onSwitchToRider = {
                        keyManager.setUserMode(SecureKeyStorage.UserMode.RIDER)
                        currentMode = SecureKeyStorage.UserMode.RIDER
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
