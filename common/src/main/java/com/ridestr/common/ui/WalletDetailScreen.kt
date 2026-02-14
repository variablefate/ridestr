package com.ridestr.common.ui

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.payment.*
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.Image
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Full wallet detail screen.
 * Accessed by tapping the wallet balance card.
 *
 * Features:
 * - Large balance display with currency toggle
 * - Deposit (receive) and Withdraw (send) buttons
 * - Provider info with option to change
 * - Transaction history
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailScreen(
    walletService: WalletService,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    onBack: () -> Unit,
    initialDepositAmount: Long? = null,  // If set, auto-open deposit dialog with this amount
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val balance by walletService.balance.collectAsState()
    val isConnected by walletService.isConnected.collectAsState()
    val mintName by walletService.currentMintName.collectAsState()
    val transactions by walletService.transactions.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPriceUsd by priceService.btcPriceUsd.collectAsState()

    // Dialog state - auto-open deposit dialog if initialDepositAmount is provided
    var showDepositDialog by remember { mutableStateOf(initialDepositAmount != null) }
    var showWithdrawDialog by remember { mutableStateOf(false) }
    var showChangeMintDialog by remember { mutableStateOf(false) }

    // Track the initial deposit amount for pre-filling
    val prefilledDepositAmount = remember { initialDepositAmount }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                // Set isRefreshing BEFORE launch to avoid race condition
                // (launch returns immediately, but PullToRefreshBox checks isRefreshing right away)
                isRefreshing = true
                scope.launch {
                    try {
                        walletService.refreshBalance()
                    } finally {
                        isRefreshing = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
            // Balance Card
            item {
                BalanceDisplayCard(
                    balance = balance,
                    displayCurrency = displayCurrency,
                    btcPriceUsd = btcPriceUsd,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Deposit Button
                    Button(
                        onClick = { showDepositDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = isConnected
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Deposit")
                    }

                    // Withdraw Button
                    OutlinedButton(
                        onClick = { showWithdrawDialog = true },
                        modifier = Modifier.weight(1f),
                        enabled = isConnected && balance.availableSats > 0
                    ) {
                        Icon(Icons.Default.Remove, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Withdraw")
                    }
                }
            }

            // Provider Card
            item {
                ProviderCard(
                    providerName = mintName,
                    isConnected = isConnected,
                    onChangeMint = { showChangeMintDialog = true }
                )
            }

            // Transaction History Header
            item {
                Text(
                    text = "Transaction History",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }

            // Transactions
            if (transactions.isEmpty()) {
                item {
                    EmptyTransactionsCard()
                }
            } else {
                items(transactions) { tx ->
                    TransactionItem(
                        transaction = tx,
                        displayCurrency = displayCurrency,
                        btcPriceUsd = btcPriceUsd
                    )
                }
            }
        }
        }
    }

    // Dialogs
    if (showDepositDialog) {
        DepositDialog(
            walletService = walletService,
            initialAmount = prefilledDepositAmount,
            onDismiss = {
                showDepositDialog = false
                // Trigger a background refresh after a short delay to clean up pending deposits
                // This catches payments that completed after the user closed the dialog
                scope.launch {
                    delay(3000) // Wait 3 seconds
                    walletService.refreshBalance()
                }
            }
        )
    }

    if (showWithdrawDialog) {
        WithdrawDialog(
            walletService = walletService,
            settingsManager = settingsManager,
            maxAmount = balance.availableSats,
            onDismiss = { showWithdrawDialog = false }
        )
    }

    if (showChangeMintDialog) {
        ChangeMintDialog(
            walletService = walletService,
            currentBalance = balance.availableSats,
            onDismiss = { showChangeMintDialog = false }
        )
    }
}

@Composable
private fun BalanceDisplayCard(
    balance: WalletBalance,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    onToggleCurrency: () -> Unit
) {
    val balanceDisplay = formatSats(balance.availableSats, displayCurrency, btcPriceUsd)
    val secondaryDisplay = formatSats(
        balance.availableSats,
        if (displayCurrency == DisplayCurrency.SATS) DisplayCurrency.USD else DisplayCurrency.SATS,
        btcPriceUsd
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = "Toggle currency", onClick = onToggleCurrency)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Available Balance",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = balanceDisplay,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = secondaryDisplay,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )

            if (balance.pendingSats > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text("+ ${formatSats(balance.pendingSats, displayCurrency, btcPriceUsd)} pending")
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(16.dp))
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap to toggle currency",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ProviderCard(
    providerName: String?,
    isConnected: Boolean,
    onChangeMint: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.CloudDone else Icons.Default.CloudOff,
                contentDescription = null,
                tint = if (isConnected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Wallet Provider",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = providerName ?: "Not connected",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(onClick = onChangeMint) {
                Text("Change")
            }
        }
    }
}

@Composable
private fun EmptyTransactionsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Receipt,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "No transactions yet",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Deposit funds to get started",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TransactionItem(
    transaction: PaymentTransaction,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?
) {
    val context = LocalContext.current
    val (icon, tint, prefix) = when (transaction.type) {
        TransactionType.ESCROW_LOCK -> Triple(
            Icons.Default.Lock,
            MaterialTheme.colorScheme.tertiary,
            "-"
        )
        TransactionType.ESCROW_RECEIVE -> Triple(
            Icons.Default.LockOpen,
            MaterialTheme.colorScheme.primary,
            "+"
        )
        TransactionType.SETTLEMENT -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            "+"
        )
        TransactionType.REFUND -> Triple(
            Icons.Default.Replay,
            MaterialTheme.colorScheme.secondary,
            "+"
        )
        TransactionType.DEPOSIT -> Triple(
            Icons.Default.Add,
            MaterialTheme.colorScheme.primary,
            "+"
        )
        TransactionType.WITHDRAWAL -> Triple(
            Icons.Default.Remove,
            MaterialTheme.colorScheme.error,
            "-"
        )
        TransactionType.ESCROW_REFUND -> Triple(
            Icons.Default.Refresh,
            MaterialTheme.colorScheme.secondary,
            "+"
        )
        TransactionType.BRIDGE_PAYMENT -> Triple(
            Icons.Default.SwapHoriz,
            MaterialTheme.colorScheme.tertiary,
            "-"
        )
    }

    val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }

    // Deposits and withdrawals have quote IDs that can be copied
    val hasQuoteId = transaction.type == TransactionType.DEPOSIT ||
            transaction.type == TransactionType.WITHDRAWAL

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (hasQuoteId) {
                    Modifier.clickable {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Quote ID", transaction.id))
                        android.widget.Toast.makeText(context, "Quote ID copied", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else Modifier
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = transaction.status,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = dateFormat.format(Date(transaction.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Show quote ID hint for deposits/withdrawals
                if (hasQuoteId) {
                    Text(
                        text = "Tap to copy quote ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "$prefix${formatSats(transaction.amountSats, displayCurrency, btcPriceUsd)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = tint
                )
                // Show fee if present (for withdrawals, bridge payments, etc.)
                if (transaction.feeSats > 0) {
                    Text(
                        text = "+ ${formatSats(transaction.feeSats, displayCurrency, btcPriceUsd)} fee",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ================================
// Deposit Dialog
// ================================

@Composable
private fun DepositDialog(
    walletService: WalletService,
    initialAmount: Long? = null,
    onDismiss: () -> Unit
) {
    var amount by remember { mutableStateOf(initialAmount?.toString() ?: "") }
    var isLoading by remember { mutableStateOf(false) }
    var invoice by remember { mutableStateOf<String?>(null) }
    var quoteId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isPaid by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Deposit Funds") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    invoice == null -> {
                        // Step 1: Enter amount
                        Text("Enter the amount you want to deposit:")
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { amount = it.filter { c -> c.isDigit() } },
                            label = { Text("Amount (sats)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("1000") }
                        )
                        Text(
                            text = "A Lightning invoice will be generated for you to pay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    !isPaid -> {
                        // Step 2: Show invoice
                        Text("Pay this Lightning invoice:")

                        // Invoice display with QR code
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Real QR code
                                QrCodeImage(
                                    content = invoice!!,
                                    modifier = Modifier.size(180.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = invoice!!.take(40) + "...",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Action buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Copy button
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Lightning Invoice", invoice))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }

                            // Open in wallet button
                            Button(
                                onClick = {
                                    try {
                                        val lightningUri = if (invoice!!.lowercase().startsWith("lightning:")) {
                                            invoice
                                        } else {
                                            "lightning:$invoice"
                                        }
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(lightningUri))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // No wallet app installed - fall back to copy
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Lightning Invoice", invoice))
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.AccountBalanceWallet, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Open Wallet")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Waiting for payment...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    else -> {
                        // Step 3: Success
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Deposit Complete!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${amount} sats added to your wallet",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                error?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Icon(
                                Icons.Default.Error,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                isPaid -> {
                    Button(onClick = onDismiss) { Text("Done") }
                }
                invoice == null -> {
                    Button(
                        onClick = {
                            val sats = amount.toLongOrNull()
                            if (sats == null || sats <= 0) {
                                error = "Please enter a valid amount"
                                return@Button
                            }
                            isLoading = true
                            error = null
                            scope.launch {
                                walletService.requestDeposit(sats).fold(
                                    onSuccess = { quote ->
                                        invoice = quote.request
                                        quoteId = quote.quote
                                        // Start polling for payment
                                        pollForDeposit(walletService, quote.quote) {
                                            isPaid = true
                                        }
                                    },
                                    onFailure = { error = it.message ?: "Failed to generate invoice" }
                                )
                                isLoading = false
                            }
                        },
                        enabled = amount.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Generate Invoice")
                    }
                }
                else -> {
                    // Waiting for payment - no confirm button
                }
            }
        },
        dismissButton = {
            if (!isPaid && !isLoading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun kotlinx.coroutines.CoroutineScope.pollForDeposit(
    walletService: WalletService,
    quoteId: String,
    onPaid: () -> Unit
) {
    launch {
        repeat(60) { // Poll for 5 minutes max
            delay(5000) // Check every 5 seconds
            val result = walletService.checkDepositStatus(quoteId)
            if (result.getOrNull() == true) {
                onPaid()
                return@launch
            }
        }
    }
}

// ================================
// Withdraw Dialog
// ================================

@Composable
private fun WithdrawDialog(
    walletService: WalletService,
    settingsManager: SettingsManager,
    maxAmount: Long,
    onDismiss: () -> Unit
) {
    var invoice by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<MeltQuote?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isComplete by remember { mutableStateOf(false) }

    // LN address flow: need amount input before resolving
    var lnAddress by remember { mutableStateOf<String?>(null) }
    var amountInput by remember { mutableStateOf("") }

    // Favorite addresses (Issue #14)
    val favoriteAddresses by settingsManager.favoriteLnAddresses.collectAsState()
    var showSaveToFavorites by remember { mutableStateOf(false) }
    var savedToFavorites by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Withdraw Funds") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    // Step 1: Enter Lightning invoice or address
                    quote == null && lnAddress == null -> {
                        Text("Available: ${"%,d".format(maxAmount)} sats")

                        OutlinedTextField(
                            value = invoice,
                            onValueChange = { invoice = it },
                            label = { Text("Lightning Invoice or Address") },
                            placeholder = { Text("lnbc... or user@domain.com") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )

                        Text(
                            text = "Paste a Lightning invoice or enter a Lightning address (user@domain.com)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Favorite addresses (Issue #14)
                        if (favoriteAddresses.isNotEmpty()) {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                favoriteAddresses.sortedByDescending { it.lastUsed }.forEach { fav ->
                                    AssistChip(
                                        onClick = { invoice = fav.address },
                                        label = {
                                            Text(
                                                text = fav.label ?: fav.address,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Star,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Step 1.5: LN address detected - enter amount
                    quote == null && lnAddress != null -> {
                        Text("Sending to: $lnAddress")

                        Text("Available: ${"%,d".format(maxAmount)} sats")

                        OutlinedTextField(
                            value = amountInput,
                            onValueChange = { newValue ->
                                // Only allow digits
                                if (newValue.all { it.isDigit() }) {
                                    amountInput = newValue
                                }
                            },
                            label = { Text("Amount (sats)") },
                            placeholder = { Text("Enter amount to withdraw") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Text(
                            text = "Enter the amount you want to withdraw in satoshis",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Step 2: Confirm fee
                    !isComplete -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Amount")
                                    Text("${"%,d".format(quote!!.amount)} sats")
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Network Fee")
                                    Text("${"%,d".format(quote!!.feeReserve)} sats")
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Total", fontWeight = FontWeight.Bold)
                                    Text(
                                        "${"%,d".format(quote!!.totalAmount)} sats",
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (quote!!.totalAmount > maxAmount) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = "Insufficient funds. You need ${quote!!.totalAmount - maxAmount} more sats.",
                                    modifier = Modifier.padding(12.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }

                    // Step 3: Success
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(64.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Withdrawal Complete!",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )

                            // Show save to favorites option for LN addresses (Issue #14)
                            if (lnAddress != null && !settingsManager.isFavoriteLnAddress(lnAddress!!)) {
                                Spacer(modifier = Modifier.height(16.dp))
                                if (!savedToFavorites) {
                                    OutlinedButton(
                                        onClick = {
                                            settingsManager.addFavoriteLnAddress(lnAddress!!)
                                            savedToFavorites = true
                                        }
                                    ) {
                                        Icon(
                                            Icons.Default.Star,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Add to Favorites")
                                    }
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Saved to favorites",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            } else if (lnAddress != null) {
                                // Already in favorites - update last used
                                LaunchedEffect(Unit) {
                                    settingsManager.updateFavoriteLastUsed(lnAddress!!)
                                }
                            }
                        }
                    }
                }

                error?.let {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                isComplete -> {
                    Button(onClick = onDismiss) { Text("Done") }
                }

                // Step 1: Detect if LN address or get quote for BOLT11
                quote == null && lnAddress == null -> {
                    Button(
                        onClick = {
                            if (invoice.isBlank()) {
                                error = "Please enter an invoice or address"
                                return@Button
                            }

                            // Check if it's a Lightning address
                            if (invoice.contains("@") && !invoice.startsWith("lnbc")) {
                                // LN address - need amount first
                                lnAddress = invoice.trim()
                                error = null
                            } else {
                                // BOLT11 invoice - get quote directly
                                isLoading = true
                                error = null
                                scope.launch {
                                    walletService.getMeltQuote(invoice.trim()).fold(
                                        onSuccess = { quote = it },
                                        onFailure = { error = it.message ?: "Failed to get quote" }
                                    )
                                    isLoading = false
                                }
                            }
                        },
                        enabled = invoice.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Continue")
                    }
                }

                // Step 1.5: Resolve LN address with amount
                quote == null && lnAddress != null -> {
                    Button(
                        onClick = {
                            val amount = amountInput.toLongOrNull()
                            if (amount == null || amount <= 0) {
                                error = "Please enter a valid amount"
                                return@Button
                            }
                            if (amount > maxAmount) {
                                error = "Amount exceeds available balance"
                                return@Button
                            }

                            isLoading = true
                            error = null
                            scope.launch {
                                // Resolve LN address to BOLT11 with amount
                                walletService.resolveLnAddress(lnAddress!!, amount).fold(
                                    onSuccess = { bolt11 ->
                                        // Now get the melt quote
                                        walletService.getMeltQuote(bolt11).fold(
                                            onSuccess = { quote = it },
                                            onFailure = { error = it.message ?: "Failed to get quote" }
                                        )
                                    },
                                    onFailure = { error = it.message ?: "Failed to resolve Lightning address" }
                                )
                                isLoading = false
                            }
                        },
                        enabled = amountInput.isNotBlank() && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Get Quote")
                    }
                }

                // Step 2: Confirm withdrawal
                else -> {
                    Button(
                        onClick = {
                            isLoading = true
                            error = null
                            scope.launch {
                                walletService.executeWithdraw(quote!!).fold(
                                    onSuccess = { isComplete = true },
                                    onFailure = { error = it.message ?: "Withdrawal failed" }
                                )
                                isLoading = false
                            }
                        },
                        enabled = !isLoading && quote!!.totalAmount <= maxAmount
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Confirm Withdraw")
                    }
                }
            }
        },
        dismissButton = {
            when {
                isComplete || isLoading -> { /* No dismiss button */ }
                lnAddress != null && quote == null -> {
                    // Allow going back from amount step
                    TextButton(onClick = {
                        lnAddress = null
                        amountInput = ""
                        error = null
                    }) { Text("Back") }
                }
                else -> {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    )
}

// ================================
// Change Mint Dialog
// ================================

@Composable
private fun ChangeMintDialog(
    walletService: WalletService,
    currentBalance: Long,
    onDismiss: () -> Unit
) {
    var selectedMint by remember { mutableStateOf<MintOption?>(null) }
    var customUrl by remember { mutableStateOf("") }
    var showCustom by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text("Change Wallet Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Warning if balance exists
                if (currentBalance > 0) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "You have ${"%,d".format(currentBalance)} sats!",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = "Withdraw your funds before switching providers, or they will be lost.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Mint selection
                WalletService.DEFAULT_MINTS.forEach { option ->
                    MintSelectionRow(
                        mint = option,
                        isSelected = selectedMint == option && !showCustom,
                        onSelect = {
                            selectedMint = option
                            showCustom = false
                            error = null
                        }
                    )
                }

                // Custom option
                MintSelectionRow(
                    mint = MintOption("Custom", "Enter your own URL", "", false),
                    isSelected = showCustom,
                    onSelect = {
                        showCustom = true
                        selectedMint = null
                        error = null
                    }
                )

                // Custom URL field
                if (showCustom) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = {
                            customUrl = it
                            error = null
                        },
                        label = { Text("Provider URL") },
                        placeholder = { Text("https://...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val url = if (showCustom) customUrl.trim() else selectedMint?.url
                    if (url.isNullOrBlank()) {
                        error = "Please select a provider"
                        return@Button
                    }
                    isLoading = true
                    error = null
                    scope.launch {
                        // Verify first
                        val capabilities = walletService.verifyMint(url)
                        if (capabilities == null) {
                            error = "Couldn't connect to provider"
                            isLoading = false
                            return@launch
                        }
                        if (!capabilities.supportsEscrow()) {
                            error = "This provider doesn't support ride payments"
                            isLoading = false
                            return@launch
                        }

                        // Disconnect from current mint
                        walletService.disconnect()

                        // Connect to new mint
                        val success = walletService.connect(url)
                        isLoading = false

                        if (success) {
                            onDismiss()
                        } else {
                            error = "Failed to connect"
                        }
                    }
                },
                enabled = !isLoading && (selectedMint != null || (showCustom && customUrl.isNotBlank()))
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (currentBalance > 0) "Switch Anyway" else "Switch Provider")
            }
        },
        dismissButton = {
            if (!isLoading) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

@Composable
private fun MintSelectionRow(
    mint: MintOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = mint.name,
                    fontWeight = FontWeight.Medium
                )
                if (mint.recommended) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "(Recommended)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (mint.description.isNotBlank()) {
                Text(
                    text = mint.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ================================
// Utility
// ================================

private fun formatSats(sats: Long, displayCurrency: DisplayCurrency, btcPriceUsd: Int?): String {
    return when (displayCurrency) {
        DisplayCurrency.SATS -> "%,d sats".format(sats)
        DisplayCurrency.USD -> {
            val usd = btcPriceUsd?.let { sats.toDouble() * it / 100_000_000.0 }
            usd?.let { String.format("$%.2f", it) } ?: "%,d sats".format(sats)
        }
    }
}

// ================================
// QR Code Generation
// ================================

/**
 * Generate a QR code bitmap from a string.
 * Uses ZXing library for generation.
 */
@Composable
private fun QrCodeImage(
    content: String,
    modifier: Modifier = Modifier,
    size: Int = 512
) {
    val bitmap = remember(content) {
        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
            bmp
        } catch (e: Exception) {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR Code",
            modifier = modifier
        )
    } else {
        // Fallback to placeholder icon
        Icon(
            Icons.Default.QrCode2,
            contentDescription = "QR Code",
            modifier = modifier,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
