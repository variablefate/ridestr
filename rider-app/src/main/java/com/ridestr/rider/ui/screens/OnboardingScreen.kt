package com.ridestr.rider.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.rider.viewmodels.OnboardingUiState
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
                uiState = uiState,
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

@Composable
private fun KeySetupScreen(
    uiState: OnboardingUiState,
    onGenerateKey: () -> Unit,
    onImportKey: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showImportField by remember { mutableStateOf(false) }
    var keyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Ridestr",
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Decentralized ridesharing for riders",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onGenerateKey,
            enabled = !uiState.isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isLoading && !showImportField) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create New Identity")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "  or  ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (showImportField) {
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text("Enter nsec or hex key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "Hide" else "Show")
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showImportField = false; keyInput = "" },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onImportKey(keyInput) },
                    enabled = !uiState.isLoading && keyInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Import")
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { showImportField = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Existing Key")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Your key is stored securely on this device. " +
                    "Make sure to backup your nsec - it's the only way to recover your identity.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun BackupReminderScreen(
    nsec: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNsec by remember { mutableStateOf(false) }
    var hasCopied by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Backup Your Key",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Important!",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Your private key (nsec) is the only way to access your Nostr identity. " +
                            "If you lose it, you cannot recover your account. " +
                            "Store it somewhere safe and never share it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Your Private Key",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (showNsec) nsec else "nsec1••••••••••••••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showNsec = !showNsec }
                    ) {
                        Text(if (showNsec) "Hide" else "Reveal")
                    }
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(nsec))
                            hasCopied = true
                        },
                        enabled = showNsec
                    ) {
                        Text(if (hasCopied) "Copied!" else "Copy")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("I've Saved My Key")
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onDismiss
        ) {
            Text("Skip for now")
        }
    }
}
