package com.ridestr.common.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.payment.MintOption
import com.ridestr.common.payment.WalletService
import com.ridestr.common.payment.cashu.MintCapabilities
import kotlinx.coroutines.launch

/**
 * Wallet setup screen for onboarding.
 *
 * Allows users to:
 * - Restore existing NIP-60 wallet
 * - Select a wallet provider (mint)
 * - Enter custom mint URL
 * - Skip setup for later
 *
 * Design: Simple, user-friendly. No technical jargon like "mint", "proofs", "NUT-14".
 */
@Composable
fun WalletSetupScreen(
    walletService: WalletService,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    // State
    var selectedMint by remember { mutableStateOf<MintOption?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var verificationError by remember { mutableStateOf<String?>(null) }
    var checkingNip60 by remember { mutableStateOf(true) }
    var hasExistingWallet by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoredBalance by remember { mutableStateOf<Long?>(null) }
    var showStartFreshConfirmation by remember { mutableStateOf(false) }
    var showRestoreFailedDialog by remember { mutableStateOf(false) }

    // Check for existing NIP-60 wallet on launch
    LaunchedEffect(Unit) {
        checkingNip60 = true
        hasExistingWallet = walletService.hasExistingNip60Wallet()
        checkingNip60 = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Set Up Your Wallet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Needed to pay for rides",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Content
        when {
            checkingNip60 -> {
                // Loading state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Checking for existing wallet...")
                    }
                }
            }

            hasExistingWallet && restoredBalance == null -> {
                // Found existing wallet - offer restore
                ExistingWalletCard(
                    isRestoring = isRestoring,
                    onRestore = {
                        scope.launch {
                            isRestoring = true
                            val success = walletService.restoreFromNip60()
                            if (success) {
                                restoredBalance = walletService.getBalance()
                            } else {
                                // Show error dialog instead of silently falling through
                                showRestoreFailedDialog = true
                            }
                            isRestoring = false
                        }
                    },
                    onStartFresh = {
                        showStartFreshConfirmation = true
                    }
                )

                // Restore failed dialog
                if (showRestoreFailedDialog) {
                    AlertDialog(
                        onDismissRequest = { showRestoreFailedDialog = false },
                        icon = {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        title = { Text("Restore Failed") },
                        text = {
                            Text(
                                "Could not restore your wallet from NIP-60 backup. This may happen if:\n\n" +
                                "\u2022 The backup is corrupted\n" +
                                "\u2022 The mint is unreachable\n" +
                                "\u2022 Network issues\n\n" +
                                "Your wallet data still exists on Nostr. You can try again or start fresh (which will overwrite your existing backup)."
                            )
                        },
                        confirmButton = {
                            Button(onClick = { showRestoreFailedDialog = false }) {
                                Text("Try Again")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    showRestoreFailedDialog = false
                                    showStartFreshConfirmation = true
                                }
                            ) {
                                Text("Start Fresh")
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            restoredBalance != null -> {
                // Successfully restored
                RestoredWalletCard(
                    balance = restoredBalance!!,
                    onContinue = onComplete
                )
                Spacer(modifier = Modifier.weight(1f))
            }

            else -> {
                // Confirmation dialog for "Start Fresh" when existing wallet found
                if (showStartFreshConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showStartFreshConfirmation = false },
                        icon = {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        title = { Text("Replace Existing Wallet?") },
                        text = {
                            Text(
                                "An existing NIP-60 wallet backup was found on Nostr. Starting fresh will:\n\n" +
                                "\u2022 Create a new wallet key\n" +
                                "\u2022 Overwrite your NIP-60 backup\n" +
                                "\u2022 Make the old wallet unrecoverable\n\n" +
                                "If you have ecash in another app (Minibits, nutstash, etc.) using this Nostr key, those funds may become inaccessible.\n\n" +
                                "Only proceed if the existing wallet is empty or you have another backup."
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    showStartFreshConfirmation = false
                                    hasExistingWallet = false
                                },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text("Start Fresh Anyway")
                            }
                        },
                        dismissButton = {
                            Button(onClick = { showStartFreshConfirmation = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                // New wallet setup - provider selection
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = "Choose a wallet provider:",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    items(WalletService.DEFAULT_MINTS) { mint ->
                        MintOptionCard(
                            mint = mint,
                            isSelected = selectedMint == mint,
                            onSelect = {
                                selectedMint = mint
                                showCustomInput = false
                                verificationError = null
                            }
                        )
                    }

                    item {
                        // Custom option
                        CustomMintCard(
                            isSelected = showCustomInput,
                            customUrl = customUrl,
                            onSelect = {
                                showCustomInput = true
                                selectedMint = null
                                verificationError = null
                            },
                            onUrlChange = {
                                customUrl = it
                                verificationError = null
                            }
                        )
                    }

                    // Error message
                    if (verificationError != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = verificationError!!,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }

                // Buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val mintUrl = if (showCustomInput) {
                                    customUrl.trim().ifEmpty { null }
                                } else {
                                    selectedMint?.url
                                }

                                if (mintUrl == null) {
                                    verificationError = "Please select a wallet provider"
                                    return@launch
                                }

                                isVerifying = true
                                verificationError = null

                                val capabilities = walletService.verifyMint(mintUrl)
                                if (capabilities == null) {
                                    verificationError = "Couldn't connect. Check the URL and try again."
                                    isVerifying = false
                                    return@launch
                                }

                                if (!capabilities.supportsEscrow()) {
                                    verificationError = "This provider doesn't support ride payments. Try LN Server instead."
                                    isVerifying = false
                                    return@launch
                                }

                                // Connect!
                                val connected = walletService.connect(mintUrl)
                                isVerifying = false

                                if (connected) {
                                    // Sync wallet metadata to Nostr for backup/restore
                                    walletService.syncToNip60()
                                    onComplete()
                                } else {
                                    verificationError = "Failed to connect. Please try again."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isVerifying && (selectedMint != null || (showCustomInput && customUrl.isNotBlank()))
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Connecting...")
                        } else {
                            Text("Continue")
                        }
                    }

                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Skip for now")
                    }
                }
            }
        }
    }
}

@Composable
private fun MintOptionCard(
    mint: MintOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelect
            ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mint.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (mint.recommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        AssistChip(
                            onClick = {},
                            label = { Text("Recommended", style = MaterialTheme.typography.labelSmall) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                Text(
                    text = mint.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CustomMintCard(
    isSelected: Boolean,
    customUrl: String,
    onSelect: () -> Unit,
    onUrlChange: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelect
            ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = isSelected,
                    onClick = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Custom",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Enter your own provider URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = onUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Wallet provider URL") },
                    placeholder = { Text("https://mint.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
            }
        }
    }
}

@Composable
private fun ExistingWalletCard(
    isRestoring: Boolean,
    onRestore: () -> Unit,
    onStartFresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CloudDone,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Found Existing Wallet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "We found a wallet linked to your account. Would you like to restore it?",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onRestore,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRestoring
            ) {
                if (isRestoring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restoring...")
                } else {
                    Icon(Icons.Default.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore Wallet")
                }
            }

            TextButton(
                onClick = onStartFresh,
                enabled = !isRestoring
            ) {
                Text("Start Fresh Instead")
            }
        }
    }
}

@Composable
private fun RestoredWalletCard(
    balance: Long,
    onContinue: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Wallet Restored!",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "%,d sats".format(balance),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Continue")
            }
        }
    }
}
