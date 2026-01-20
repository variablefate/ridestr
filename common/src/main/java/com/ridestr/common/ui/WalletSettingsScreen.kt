package com.ridestr.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ridestr.common.payment.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Consolidated wallet settings screen.
 * Manages Cashu wallet connection, sync, and diagnostics.
 *
 * This screen replaces wallet-related settings previously scattered across
 * Developer Options and Account Safety screens.
 *
 * Key principle: NIP-60 IS the wallet (source of truth).
 * cdk-kotlin is only used for mint API calls (deposit/withdraw).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletSettingsScreen(
    walletService: WalletService,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isConnected by walletService.isConnected.collectAsState()
    val balance by walletService.balance.collectAsState()
    val mintName by walletService.currentMintName.collectAsState()
    val diagnostics by walletService.diagnostics.collectAsState()

    val scope = rememberCoroutineScope()

    // Sync state
    var isSyncing by remember { mutableStateOf(false) }
    var lastSyncResult by remember { mutableStateOf<SyncResult?>(null) }

    // Dialog states
    var showChangeMintDialog by remember { mutableStateOf(false) }
    var showResetLocalDialog by remember { mutableStateOf(false) }
    var showDeleteNip60Dialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wallet Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // === Section: Mint Connection ===
            Text(
                text = "Mint Connection",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
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
                                text = mintName ?: "Not connected",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (isConnected) "Connected" else "Disconnected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = { showChangeMintDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Change Mint")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Section: Sync & Diagnostics ===
            Text(
                text = "Sync & Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Current balance
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Verified Balance",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${"%,d".format(balance.availableSats)} sats",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // NIP-60 proofs
                    diagnostics?.let { diag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "NIP-60 Proofs",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Row {
                                Text(
                                    text = "${diag.nip60ProofCount ?: 0} proofs",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (diag.isNip60Synced) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Synced",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Unverified proofs (if any)
                        if (diag.unverifiedCount > 0) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = "Unverified Proofs",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${diag.unverifiedCount} proofs (${"%,d".format(diag.unverifiedBalance)} sats) need verification",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Last sync
                        diag.lastNip60Sync?.let { syncTime ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Last Sync",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                val dateFormat = remember { SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()) }
                                Text(
                                    text = dateFormat.format(Date(syncTime)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Issues
                        if (diag.hasIssues) {
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(12.dp))
                            diag.issues.forEach { issue ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = issue,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // Last sync result
                    lastSyncResult?.let { result ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (result.success)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (result.success)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = result.message,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Sync button
                    Button(
                        onClick = {
                            scope.launch {
                                isSyncing = true
                                lastSyncResult = null
                                try {
                                    lastSyncResult = walletService.syncWallet()
                                } catch (e: Exception) {
                                    lastSyncResult = SyncResult(false, "Error: ${e.message}")
                                }
                                isSyncing = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isConnected && !isSyncing
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Syncing...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Sync Wallet")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Section: HTLC Recovery ===
            Text(
                text = "HTLC Escrow Recovery",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // State for pending HTLCs
                    var pendingHtlcs by remember { mutableStateOf(walletService.getPendingHtlcs()) }
                    var refundableHtlcs by remember { mutableStateOf(walletService.getRefundableHtlcs()) }
                    var isRefunding by remember { mutableStateOf(false) }
                    var refundResult by remember { mutableStateOf<String?>(null) }

                    val totalPending = pendingHtlcs.sumOf { it.amountSats }
                    val totalRefundable = refundableHtlcs.sumOf { it.amountSats }

                    Text(
                        text = "Pending HTLCs: ${pendingHtlcs.size} (${"%,d".format(totalPending)} sats)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Refundable (expired): ${refundableHtlcs.size} (${"%,d".format(totalRefundable)} sats)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (refundableHtlcs.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Show each pending HTLC
                    if (pendingHtlcs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        pendingHtlcs.take(5).forEach { htlc ->
                            val expiresIn = htlc.locktime - (System.currentTimeMillis() / 1000)
                            val expiryText = if (expiresIn > 0) {
                                val mins = expiresIn / 60
                                if (mins > 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
                            } else "EXPIRED"
                            Text(
                                text = "• ${htlc.amountSats} sats - $expiryText",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (expiresIn <= 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (pendingHtlcs.size > 5) {
                            Text(
                                text = "...and ${pendingHtlcs.size - 5} more",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Refund result
                    refundResult?.let { result ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.contains("success", ignoreCase = true))
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Refresh button
                        OutlinedButton(
                            onClick = {
                                pendingHtlcs = walletService.getPendingHtlcs()
                                refundableHtlcs = walletService.getRefundableHtlcs()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh")
                        }

                        // Try Refund button
                        Button(
                            onClick = {
                                scope.launch {
                                    isRefunding = true
                                    refundResult = null
                                    try {
                                        val results = walletService.refundExpiredHtlcs()
                                        val successCount = results.count { it.success }
                                        val totalRefunded = results.filter { it.success }.sumOf { it.amountSats }
                                        refundResult = if (successCount > 0) {
                                            "Recovered $successCount HTLCs ($totalRefunded sats)"
                                        } else if (results.isEmpty()) {
                                            "No expired HTLCs to refund"
                                        } else {
                                            "Failed to refund ${results.size} HTLCs"
                                        }
                                        // Refresh lists
                                        pendingHtlcs = walletService.getPendingHtlcs()
                                        refundableHtlcs = walletService.getRefundableHtlcs()
                                    } catch (e: Exception) {
                                        refundResult = "Error: ${e.message}"
                                    }
                                    isRefunding = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isRefunding && refundableHtlcs.isNotEmpty()
                        ) {
                            if (isRefunding) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Try Refund")
                            }
                        }
                    }

                    Text(
                        text = "HTLCs can only be refunded after their locktime expires (2 hours).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // === Section: Advanced ===
            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Recover Local Funds (sync cdk-kotlin to NIP-60)
                    var isRecovering by remember { mutableStateOf(false) }
                    var recoveryResult by remember { mutableStateOf<String?>(null) }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Recover Local Funds",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Push any funds stuck in local storage to Nostr relays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    isRecovering = true
                                    recoveryResult = null
                                    try {
                                        val result = walletService.resyncProofsToNip60()
                                        recoveryResult = if (result.success) {
                                            "Recovered ${result.proofsPublished} proofs (${result.newBalance} sats)"
                                        } else {
                                            result.message
                                        }
                                    } catch (e: Exception) {
                                        recoveryResult = "Error: ${e.message}"
                                    }
                                    isRecovering = false
                                }
                            },
                            enabled = !isRecovering && isConnected
                        ) {
                            if (isRecovering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Recover")
                            }
                        }
                    }

                    recoveryResult?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.startsWith("Recovered") || result.startsWith("No proofs"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Reset Local Cache
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reset Local Cache",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Clears local wallet data. NIP-60 proofs remain on relays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { showResetLocalDialog = true }) {
                            Text("Reset", color = MaterialTheme.colorScheme.error)
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Delete NIP-60 Data
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Delete NIP-60 Data",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Request deletion of wallet proofs from Nostr relays.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = { showDeleteNip60Dialog = true },
                            enabled = walletService.hasNip60Sync()
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // === Dialogs ===

    // Change Mint Dialog
    if (showChangeMintDialog) {
        ChangeMintSettingsDialog(
            walletService = walletService,
            currentBalance = balance.availableSats,
            onDismiss = { showChangeMintDialog = false }
        )
    }

    // Reset Local Dialog
    if (showResetLocalDialog) {
        AlertDialog(
            onDismissRequest = { showResetLocalDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Reset Local Wallet Data?") },
            text = {
                Text(
                    "This will delete all local Cashu proofs and cached balance.\n\n" +
                    "If your proofs were synced to NIP-60 relays, you can restore them by reconnecting to the mint.\n\n" +
                    "Any funds NOT synced to NIP-60 will be permanently LOST!"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        walletService.resetWallet()
                        showResetLocalDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Reset Wallet")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetLocalDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete NIP-60 Dialog
    if (showDeleteNip60Dialog) {
        var isDeleting by remember { mutableStateOf(false) }
        var deleteResult by remember { mutableStateOf<Int?>(null) }

        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteNip60Dialog = false },
            icon = {
                Icon(
                    Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete NIP-60 Wallet Data?") },
            text = {
                Column {
                    Text(
                        "This will request deletion of your Cashu proof events (Kind 7375) and wallet metadata (Kind 17375) from Nostr relays.\n\n" +
                        "Note: Relays may not honor deletion requests.\n\n" +
                        "Your LOCAL proofs will NOT be affected."
                    )

                    deleteResult?.let { count ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (count >= 0)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (count >= 0) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (count >= 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (count >= 0)
                                        "Deleted $count NIP-60 events from relays"
                                    else
                                        "Error: NIP-60 sync not available",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            deleteResult = null
                            try {
                                val count = walletService.deleteNip60Data()
                                deleteResult = count
                            } catch (e: Exception) {
                                deleteResult = -1
                            }
                            isDeleting = false
                        }
                    },
                    enabled = !isDeleting && deleteResult == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isDeleting) "Deleting..." else if (deleteResult != null) "Done" else "Delete from Nostr")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteNip60Dialog = false },
                    enabled = !isDeleting
                ) {
                    Text(if (deleteResult != null) "Close" else "Cancel")
                }
            }
        )
    }
}

/**
 * Change mint dialog for wallet settings screen.
 */
@Composable
private fun ChangeMintSettingsDialog(
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
                    MintSelectionSettingsRow(
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
                MintSelectionSettingsRow(
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

                        // Connect to new mint (this will call syncWallet automatically)
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
private fun MintSelectionSettingsRow(
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
