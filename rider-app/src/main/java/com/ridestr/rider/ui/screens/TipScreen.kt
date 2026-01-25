package com.ridestr.rider.ui.screens

import androidx.activity.compose.BackHandler
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager

/**
 * Screen for tipping the driver after a completed ride.
 *
 * Provides preset amounts and custom input.
 * Shows both integrated wallet option and external wallet option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TipScreen(
    lightningAddress: String,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onTipSent: (tipAmountSats: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current

    // Preset tip amounts in sats
    val presetAmounts = listOf(500L, 1000L, 2100L, 5000L)

    var selectedAmount by remember { mutableStateOf<Long?>(null) }
    var customAmount by remember { mutableStateOf("") }
    var showExternalWalletSection by remember { mutableStateOf(false) }

    // Calculate USD equivalent for display
    fun satsToUsd(sats: Long): String? {
        return btcPriceUsd?.let { price ->
            val usd = sats.toDouble() * price / 100_000_000.0
            String.format("$%.2f", usd)
        }
    }

    // Format amount for display
    fun formatAmount(sats: Long): String {
        return when (displayCurrency) {
            DisplayCurrency.SATS -> "$sats sats"
            DisplayCurrency.USD -> satsToUsd(sats) ?: "$sats sats"
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Tip Driver") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.VolunteerActivism,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Show Your Appreciation",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Send a tip directly to your driver",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }

            // Preset amounts
            Text(
                text = "Select Amount",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presetAmounts.forEach { amount ->
                    val isSelected = selectedAmount == amount && customAmount.isEmpty()
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedAmount = amount
                            customAmount = ""
                        },
                        label = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "$amount",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "sats",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Custom amount
            OutlinedTextField(
                value = customAmount,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        customAmount = value
                        selectedAmount = value.toLongOrNull()
                    }
                },
                label = { Text("Custom amount (sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null)
                },
                trailingIcon = {
                    if (customAmount.isNotEmpty()) {
                        customAmount.toLongOrNull()?.let { sats ->
                            satsToUsd(sats)?.let { usd ->
                                Text(
                                    text = usd,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .clickable { settingsManager.toggleDisplayCurrency() }
                                )
                            }
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Send tip button (for integrated wallet - future implementation)
            val currentAmount = if (customAmount.isNotEmpty()) {
                customAmount.toLongOrNull() ?: 0L
            } else {
                selectedAmount ?: 0L
            }

            // External wallet section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showExternalWalletSection = !showExternalWalletSection },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Pay with External Wallet",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }
                        Icon(
                            imageVector = if (showExternalWalletSection)
                                Icons.Default.ExpandLess
                            else
                                Icons.Default.ExpandMore,
                            contentDescription = null
                        )
                    }

                    if (showExternalWalletSection) {
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Lightning Address:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = lightningAddress,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Lightning Address", lightningAddress)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Open in wallet button
                        OutlinedButton(
                            onClick = {
                                // Try to open lightning: URI
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse("lightning:$lightningAddress")
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "No Lightning wallet app found",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Open in Wallet App")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Paying externally? Mark tip as sent to track it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        TextButton(
                            onClick = {
                                if (currentAmount > 0) {
                                    onTipSent(currentAmount)
                                } else {
                                    Toast.makeText(context, "Please select or enter an amount", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = currentAmount > 0
                        ) {
                            Text("Mark as Sent (${formatAmount(currentAmount)})")
                        }
                    }
                }
            }

            // Main send button (for future integrated wallet)
            Button(
                onClick = {
                    if (currentAmount > 0) {
                        // TODO: Integrate with NWC/Cashu wallet
                        // For now, just mark as sent and show external option
                        showExternalWalletSection = true
                        Toast.makeText(
                            context,
                            "Integrated wallet coming soon! Use external wallet for now.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = currentAmount > 0
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (currentAmount > 0)
                        "Send ${formatAmount(currentAmount)}"
                    else
                        "Select Amount"
                )
            }
        }
    }
}
