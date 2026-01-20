package com.drivestr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.events.RideHistoryStats
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.payment.WalletDiagnostics
import com.ridestr.common.payment.WalletService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.launch

/**
 * Driver wallet screen showing wallet balance, earnings summary and tips.
 * Click wallet balance card to view full wallet interface.
 * Click earnings card to view full earnings history.
 */
@Composable
fun WalletScreen(
    rideHistoryRepository: RideHistoryRepository,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    walletService: WalletService? = null,
    onSetupWallet: (() -> Unit)? = null,
    onOpenWalletDetail: (() -> Unit)? = null,
    onViewEarningsDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by rideHistoryRepository.stats.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPriceUsd by priceService.btcPriceUsd.collectAsState()
    val walletSetupCompleted by settingsManager.walletSetupCompleted.collectAsState()
    val walletSetupSkipped by settingsManager.walletSetupSkipped.collectAsState()
    val alwaysShowDiagnostics by settingsManager.alwaysShowWalletDiagnostics.collectAsState()

    // Wallet state (if service provided)
    val walletBalance = walletService?.balance?.collectAsState()?.value
    val isConnected = walletService?.isConnected?.collectAsState()?.value ?: false
    val currentMintName = walletService?.currentMintName?.collectAsState()?.value
    val walletDiagnostics = walletService?.diagnostics?.collectAsState()?.value

    // Fetch diagnostics on first load and when balance changes
    val scope = rememberCoroutineScope()
    LaunchedEffect(walletBalance) {
        if (walletService != null && isConnected) {
            scope.launch { walletService.updateDiagnostics() }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Wallet Balance Card - clickable to open full wallet interface
        item {
            WalletBalanceCard(
                isSetUp = walletSetupCompleted,
                isSkipped = walletSetupSkipped,
                isConnected = isConnected,
                balanceSats = walletBalance?.availableSats ?: 0,
                pendingSats = walletBalance?.pendingSats ?: 0,
                providerName = currentMintName,
                displayCurrency = displayCurrency,
                btcPriceUsd = btcPriceUsd,
                diagnostics = walletDiagnostics,
                alwaysShowDiagnostics = alwaysShowDiagnostics,
                onSetup = onSetupWallet,
                onCardClick = onOpenWalletDetail,
                onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
            )
        }

        // Earnings Section - clickable to view details
        item {
            EarningsCard(
                stats = stats,
                displayCurrency = displayCurrency,
                btcPriceUsd = btcPriceUsd,
                settingsManager = settingsManager,
                onClick = onViewEarningsDetails
            )
        }

        // Tips Section
        item {
            TipsCard()
        }
    }
}

@Composable
private fun WalletBalanceCard(
    isSetUp: Boolean,
    isSkipped: Boolean,
    isConnected: Boolean,
    balanceSats: Long,
    pendingSats: Long,
    providerName: String?,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    diagnostics: WalletDiagnostics?,
    alwaysShowDiagnostics: Boolean = false,
    onSetup: (() -> Unit)?,
    onCardClick: (() -> Unit)?,
    onToggleCurrency: () -> Unit
) {
    val isClickable = isSetUp && isConnected && onCardClick != null
    var showDiagnosticsDialog by remember { mutableStateOf(false) }

    // Show diagnostics dialog
    if (showDiagnosticsDialog && diagnostics != null) {
        AlertDialog(
            onDismissRequest = { showDiagnosticsDialog = false },
            title = { Text("Wallet Diagnostics") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DiagnosticRow("Displayed Balance", "${diagnostics.displayedBalance} sats")
                    DiagnosticRow("Nostr Balance (NIP-60)", diagnostics.nip60Balance?.let { "$it sats" } ?: "Not synced")
                    DiagnosticRow("NIP-60 Proofs", diagnostics.nip60ProofCount?.toString() ?: "N/A")
                    DiagnosticRow("Cached Balance", "${diagnostics.cachedBalance} sats")
                    if (diagnostics.unverifiedCount > 0) {
                        DiagnosticRow("Unverified Proofs", "${diagnostics.unverifiedCount} (${diagnostics.unverifiedBalance} sats)")
                    }
                    DiagnosticRow("Mint Reachable", if (diagnostics.mintReachable) "Yes" else "No")
                    DiagnosticRow("Pending Deposits", diagnostics.pendingDeposits.toString())

                    if (diagnostics.issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Issues:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        diagnostics.issues.forEach { issue ->
                            Text(
                                text = "• $issue",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "All sources in sync",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiagnosticsDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isClickable) Modifier.clickable { onCardClick?.invoke() }
                else Modifier
            ),
        colors = if (isSetUp && isConnected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccountBalanceWallet,
                        contentDescription = null,
                        tint = if (isSetUp && isConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Wallet Balance",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSetUp && isConnected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    // Show diagnostics icon: red if issues, green if synced (when always show is enabled)
                    val showIcon = diagnostics != null && (diagnostics.hasIssues || alwaysShowDiagnostics)
                    if (showIcon) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val isSynced = diagnostics?.hasIssues == false
                        Icon(
                            imageVector = if (isSynced) Icons.Default.CheckCircle else Icons.Default.Info,
                            contentDescription = if (isSynced) "Wallet synced" else "Wallet sync issues",
                            tint = if (isSynced) {
                                Color(0xFF4CAF50)  // Material Green 500 - explicit green for synced
                            } else {
                                MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                            },
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { showDiagnosticsDialog = true }
                        )
                    }
                }
                if (isClickable) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "View wallet details",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isSetUp && isConnected -> {
                    // Show balance
                    val balanceDisplay = formatSats(balanceSats, displayCurrency, btcPriceUsd)
                    Text(
                        text = balanceDisplay,
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.clickable { onToggleCurrency() }
                    )
                    if (pendingSats > 0) {
                        val pendingDisplay = formatSats(pendingSats, displayCurrency, btcPriceUsd)
                        Text(
                            text = "+ $pendingDisplay pending",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    providerName?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Provider: $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                    // Hint for tapping to view wallet details
                    if (isClickable) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Tap for deposit, withdraw, and more",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                        )
                    }
                }

                isSetUp && !isConnected -> {
                    // Set up but not connected
                    Text(
                        text = "Connecting...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                isSkipped -> {
                    // Skipped setup
                    Text(
                        text = "Wallet not set up",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Set up a wallet to receive ride payments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onSetup != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onSetup) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Set Up Wallet")
                        }
                    }
                }

                else -> {
                    // Not set up yet
                    Text(
                        text = "No wallet configured",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Add a wallet to receive ride payments",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onSetup != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onSetup) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Set Up Wallet")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EarningsCard(
    stats: RideHistoryStats,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager,
    onClick: () -> Unit
) {
    // Calculate display values
    val totalEarned = stats.totalFareSatsEarned
    val completedRides = stats.completedRides
    val totalMiles = stats.totalDistanceMiles

    val totalDisplay = formatSats(totalEarned, displayCurrency, btcPriceUsd)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Payments,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Earnings",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats summary - now with 3 items
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                EarningsStat(
                    label = "Rides",
                    value = "$completedRides",
                    icon = Icons.Default.DirectionsCar,
                    onToggleCurrency = null
                )
                EarningsStat(
                    label = "Miles",
                    value = String.format("%.1f", totalMiles),
                    icon = Icons.Default.Route,
                    onToggleCurrency = null
                )
                EarningsStat(
                    label = "Earned",
                    value = totalDisplay,
                    icon = Icons.Default.Savings,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
            }

            if (completedRides > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Tap to view full earnings history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Complete rides to start earning!\nPayments are sent directly to your wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun EarningsStat(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onToggleCurrency: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (onToggleCurrency != null) {
                Modifier.clickable { onToggleCurrency() }
            } else {
                Modifier
            }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TipsCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Tips",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No tips received yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Provide great service and riders may tip you!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSats(sats: Long, displayCurrency: DisplayCurrency, btcPriceUsd: Int?): String {
    return when (displayCurrency) {
        DisplayCurrency.SATS -> "%,d sats".format(sats)
        DisplayCurrency.USD -> {
            val usd = btcPriceUsd?.let { sats.toDouble() * it / 100_000_000.0 }
            usd?.let { String.format("$%.2f", it) } ?: "%,d sats".format(sats)
        }
    }
}
