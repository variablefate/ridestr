package com.ridestr.rider.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ridestr.common.ui.screens.BackupReminderScreen
import com.ridestr.common.ui.screens.KeySetupScreen
import com.ridestr.rider.viewmodels.OnboardingViewModel

/**
 * @param onComplete Called when onboarding completes. The boolean indicates if the
 *                   user imported an existing key (true) vs generated a new one (false).
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: (wasKeyImport: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // Track whether this was a key import (for profile sync)
    var wasKeyImport by remember { mutableStateOf(false) }

    // Use snapshotFlow to ensure we read current state values, not stale ones
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.isLoggedIn to uiState.showBackupReminder }
            .collect { (isLoggedIn, showBackupReminder) ->
                if (isLoggedIn && !showBackupReminder) {
                    // If no backup reminder shown, this was a key import (imports skip the reminder)
                    // Note: wasKeyImport is set in KeySetupScreen when import button is clicked
                    onComplete(wasKeyImport)
                }
            }
    }

    when {
        uiState.showBackupReminder -> {
            // New key generation - backup reminder is shown
            BackupReminderScreen(
                nsec = viewModel.getNsecForBackup() ?: "",
                onDismiss = {
                    wasKeyImport = false  // This was a new key, not import
                    viewModel.dismissBackupReminder()
                }
            )
        }
        uiState.isLoggedIn -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        else -> {
            KeySetupScreen(
                error = uiState.error,
                isLoading = uiState.isLoading,
                headlineText = "Welcome to Ridestr",
                subtitleText = "Decentralized ridesharing for riders",
                onGenerateKey = {
                    wasKeyImport = false
                    viewModel.generateNewKey()
                },
                onImportKey = {
                    wasKeyImport = true  // This IS an import
                    viewModel.importKey(it)
                },
                modifier = modifier
            )
        }
    }
}
