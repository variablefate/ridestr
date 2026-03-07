package com.drivestr.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.drivestr.app.R
import com.drivestr.app.viewmodels.OnboardingViewModel
import com.ridestr.common.ui.screens.BackupReminderScreen
import com.ridestr.common.ui.screens.KeySetupScreen
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    // One-shot: route to caller when login completes and backup reminder is dismissed.
    LaunchedEffect(Unit) {
        snapshotFlow { uiState.isLoggedIn to uiState.showBackupReminder }
            .filter { (isLoggedIn, showBackupReminder) -> isLoggedIn && !showBackupReminder }
            .first()
        onComplete()
    }

    when {
        uiState.showBackupReminder -> {
            BackupReminderScreen(
                nsec = viewModel.getNsecForBackup() ?: "",
                onDismiss = { viewModel.dismissBackupReminder() }
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
                headlineText = "Welcome to Drivestr",
                appLogoRes = R.drawable.ic_app_logo,
                appLogoDescription = "Drivestr logo",
                onGenerateKey = { viewModel.generateNewKey() },
                onImportKey = { viewModel.importKey(it) },
                modifier = modifier
            )
        }
    }
}
