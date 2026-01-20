package com.ridestr.rider.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.CoroutineScope
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
import java.util.*

/**
 * Rider wallet screen showing wallet balance, payment methods and spending summary.
 * Click wallet balance card to view full wallet interface.
 * Click spending card to view full ride history.
 */
@Composable
fun WalletScreen(
    rideHistoryRepository: RideHistoryRepository,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    walletService: WalletService? = null,
    onSetupWallet: (() -> Unit)? = null,
    onOpenWalletDetail: (() -> Unit)? = null,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by rideHistoryRepository.stats.collectAsState()
    val rides by rideHistoryRepository.rides.collectAsState()
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

        // Payment Methods Section
        item {
            PaymentMethodsCard()
        }

        // Spending History Section - clickable to view full history
        item {
            SpendingHistoryCard(
                stats = stats,
                rides = rides,
                displayCurrency = displayCurrency,
                btcPriceUsd = btcPriceUsd,
                settingsManager = settingsManager,
                onClick = onViewHistory
            )
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
                        text = "Set up a wallet to pay for rides with Bitcoin",
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
                        text = "Add a wallet to pay for rides instantly",
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
private fun PaymentMethodsCard() {
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
                    imageVector = Icons.Default.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Payment Methods",
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
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lightning Network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pay for rides instantly with Bitcoin over Lightning",
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
private fun SpendingHistoryCard(
    stats: RideHistoryStats,
    rides: List<com.ridestr.common.nostr.events.RideHistoryEntry>,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager,
    onClick: () -> Unit
) {
    // Calculate this month's spending
    val calendar = Calendar.getInstance()
    val currentMonth = calendar.get(Calendar.MONTH)
    val currentYear = calendar.get(Calendar.YEAR)

    val thisMonthSpent = rides
        .filter { ride ->
            val rideCalendar = Calendar.getInstance().apply {
                timeInMillis = ride.timestamp * 1000
            }
            rideCalendar.get(Calendar.MONTH) == currentMonth &&
            rideCalendar.get(Calendar.YEAR) == currentYear &&
            ride.status == "completed"
        }
        .sumOf { it.fareSats }

    // Format spending values based on display currency
    val totalSpentDisplay = formatSats(stats.totalFareSatsPaid, displayCurrency, btcPriceUsd)
    val thisMonthDisplay = formatSats(thisMonthSpent, displayCurrency, btcPriceUsd)

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
                        imageVector = Icons.Default.Receipt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Spending",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SpendingStat(
                    label = "This Month",
                    value = thisMonthDisplay,
                    icon = Icons.Default.DateRange,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
                SpendingStat(
                    label = "Total Rides",
                    value = "${stats.completedRides}",
                    icon = Icons.Default.DirectionsCar,
                    onToggleCurrency = null
                )
                SpendingStat(
                    label = "All Time",
                    value = totalSpentDisplay,
                    icon = Icons.Default.Savings,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
            }

            if (stats.completedRides > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Tap to view full ride history",
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
                        text = "No rides yet!\nBook your first ride to see spending history.",
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
private fun SpendingStat(
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
