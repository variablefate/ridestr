package com.ridestr.rider.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.events.FollowedDriver
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser
import com.vitorpamplona.quartz.nip19Bech32.entities.NPub
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode

/**
 * Screen for adding a driver to RoadFlare favorites.
 *
 * Supports two methods:
 * 1. QR code scanning using Quickie (ML Kit + CameraX)
 * 2. Manual pubkey entry (npub or hex)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDriverScreen(
    followedDriversRepository: FollowedDriversRepository,
    onNavigateBack: () -> Unit,
    onDriverAdded: (FollowedDriver) -> Unit = {},
    onSendFollowNotification: (suspend (driverPubKey: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current

    var pubkeyInput by remember { mutableStateOf("") }
    var driverNote by remember { mutableStateOf("") }

    var isValidPubkey by remember { mutableStateOf(false) }
    var pubkeyHex by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var scannerError by remember { mutableStateOf<String?>(null) }

    // QR Scanner launcher
    val scanQrCodeLauncher = rememberLauncherForActivityResult(ScanQRCode()) { result ->
        when (result) {
            is QRResult.QRSuccess -> {
                val scannedContent = result.content.rawValue
                if (scannedContent != null) {
                    // Extract npub or hex from scanned content
                    val extracted = extractPubkeyFromQr(scannedContent)
                    if (extracted != null) {
                        pubkeyInput = extracted
                        scannerError = null
                    } else {
                        scannerError = "QR code doesn't contain a valid Nostr pubkey"
                    }
                }
            }
            is QRResult.QRUserCanceled -> {
                // User dismissed scanner - no action needed
            }
            is QRResult.QRMissingPermission -> {
                scannerError = "Camera permission required to scan QR codes"
            }
            is QRResult.QRError -> {
                scannerError = "Scanner error: ${result.exception.localizedMessage}"
            }
        }
    }

    // Validate pubkey when input changes
    LaunchedEffect(pubkeyInput) {
        if (pubkeyInput.isBlank()) {
            isValidPubkey = false
            pubkeyHex = null
            errorMessage = null
            return@LaunchedEffect
        }

        val trimmed = pubkeyInput.trim()

        // Try to parse as npub
        if (trimmed.startsWith("npub1")) {
            try {
                val parsed = Nip19Parser.uriToRoute(trimmed)
                if (parsed?.entity is NPub) {
                    pubkeyHex = (parsed.entity as NPub).hex
                    isValidPubkey = true
                    errorMessage = null
                    return@LaunchedEffect
                }
            } catch (e: Exception) {
                // Not a valid npub
            }
        }

        // Try to parse as hex pubkey (64 chars)
        if (trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            pubkeyHex = trimmed.lowercase()
            isValidPubkey = true
            errorMessage = null
            return@LaunchedEffect
        }

        // Invalid
        isValidPubkey = false
        pubkeyHex = null
        errorMessage = if (trimmed.startsWith("npub")) {
            "Invalid npub format"
        } else if (trimmed.length > 0) {
            "Enter an npub or 64-character hex pubkey"
        } else {
            null
        }
    }

    val scope = rememberCoroutineScope()

    fun addDriver() {
        val hex = pubkeyHex ?: return

        // Check if driver already exists
        val existingDrivers = followedDriversRepository.drivers.value
        if (existingDrivers.any { it.pubkey == hex }) {
            errorMessage = "This driver is already in your favorites"
            return
        }

        isLoading = true

        // Create driver without name - names are fetched from Nostr profiles
        val driver = FollowedDriver(
            pubkey = hex,
            addedAt = System.currentTimeMillis() / 1000,
            note = driverNote.trim(),
            roadflareKey = null  // Will be populated when driver shares key
        )

        followedDriversRepository.addDriver(driver)

        // Send a short-expiring notification so driver knows immediately
        // (p-tag query on Kind 30011 is the primary mechanism, this is just for real-time UX)
        scope.launch {
            onSendFollowNotification?.invoke(hex)
        }

        onDriverAdded(driver)
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Driver") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // QR Scanner section
            Card(
                onClick = { scanQrCodeLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Scan Driver QR Code",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        text = "Tap to scan a driver's profile QR code",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Show scanner error if any
            scannerError?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { scannerError = null }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            Text(
                text = "Or enter manually",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Pubkey input
            OutlinedTextField(
                value = pubkeyInput,
                onValueChange = { pubkeyInput = it },
                label = { Text("Driver's Nostr Pubkey") },
                placeholder = { Text("npub1... or hex pubkey") },
                isError = errorMessage != null && pubkeyInput.isNotEmpty(),
                supportingText = {
                    when {
                        errorMessage != null && pubkeyInput.isNotEmpty() -> {
                            Text(errorMessage!!, color = MaterialTheme.colorScheme.error)
                        }
                        isValidPubkey -> {
                            Text("Valid pubkey", color = MaterialTheme.colorScheme.primary)
                        }
                        else -> {
                            Text("Enter npub1... or 64-character hex")
                        }
                    }
                },
                leadingIcon = {
                    Icon(Icons.Default.Key, contentDescription = null)
                },
                trailingIcon = {
                    if (isValidPubkey) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Valid",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else if (pubkeyInput.isNotEmpty()) {
                        IconButton(onClick = { pubkeyInput = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Note input
            OutlinedTextField(
                value = driverNote,
                onValueChange = { driverNote = it },
                label = { Text("Note (optional)") },
                placeholder = { Text("e.g., Toyota Camry, great for airport runs") },
                leadingIcon = {
                    Icon(Icons.Default.Note, contentDescription = null)
                },
                maxLines = 2,
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (isValidPubkey) addDriver()
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Add button
            Button(
                onClick = { addDriver() },
                enabled = isValidPubkey && !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add to Favorites")
                }
            }

            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "How it works",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Once added, the driver will see you in their followers list. When they share their RoadFlare key, you'll be able to see their real-time location and send them ride requests.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Extract a Nostr pubkey from QR code content.
 *
 * Handles various formats:
 * - npub1... (bare npub)
 * - nostr:npub1... (nostr URI)
 * - https://...npub1... (URL containing npub)
 * - 64-character hex pubkey
 *
 * @return The pubkey (npub or hex) or null if not found
 */
private fun extractPubkeyFromQr(content: String): String? {
    val trimmed = content.trim()

    // Try direct npub
    if (trimmed.startsWith("npub1")) {
        return trimmed.split("?").first()
    }

    // Try nostr: URI
    if (trimmed.startsWith("nostr:npub1")) {
        return trimmed.removePrefix("nostr:").split("?").first()
    }

    // Try to find npub in URL or other content
    val npubRegex = Regex("npub1[a-z0-9]{58,}")
    val npubMatch = npubRegex.find(trimmed)
    if (npubMatch != null) {
        return npubMatch.value
    }

    // Try 64-char hex
    if (trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        return trimmed
    }

    // Try nostr: URI with hex
    if (trimmed.startsWith("nostr:") && trimmed.length > 6) {
        val afterPrefix = trimmed.removePrefix("nostr:")
        val hexPart = afterPrefix.split("?").first()
        if (hexPart.length == 64 && hexPart.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            return hexPart
        }
    }

    return null
}
