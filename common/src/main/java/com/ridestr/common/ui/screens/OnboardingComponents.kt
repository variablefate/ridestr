package com.ridestr.common.ui.screens

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Key setup screen for onboarding.
 * Used by both rider and driver apps with different welcome text.
 *
 * @param error Current error message, if any
 * @param isLoading Whether a key operation is in progress
 * @param headlineText App-specific headline (e.g., "Welcome to Ridestr")
 * @param appLogoRes Drawable resource ID for the app logo
 * @param appLogoDescription Accessibility description for the app logo
 * @param onGenerateKey Callback when user taps "Create New Account"
 * @param onImportKey Callback when user imports a key
 */
@Composable
fun KeySetupScreen(
    error: String?,
    isLoading: Boolean,
    headlineText: String,
    @DrawableRes appLogoRes: Int,
    appLogoDescription: String,
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
        Image(
            painter = painterResource(appLogoRes),
            contentDescription = appLogoDescription,
            modifier = Modifier.size(120.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = headlineText,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(48.dp))

        error?.let { errorText ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = errorText,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onGenerateKey,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading && !showImportField) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Create New Account")
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
                    enabled = !isLoading && keyInput.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    if (isLoading) {
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
                Text("Log In With Existing Key")
            }
        }

    }
}

/**
 * Backup reminder screen shown after generating a new key.
 * Identical for both rider and driver apps.
 *
 * @param nsec The user's private key to display and allow copying
 * @param onDismiss Callback when user acknowledges the backup
 */
@Composable
fun BackupReminderScreen(
    nsec: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNsec by remember { mutableStateOf(false) }
    var hasCopied by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
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
            text = "Backup Your Account Key",
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
                    text = "This key is your entire account.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "If you get a new phone, delete the app, switch devices, or need to log out and back in \u2014 this backup key is the only way to regain access to your wallet, ride history, favorites, and settings.\n\nThere\u2019s no \"forgot password,\" email reset, or support team that can help. Save it safely and never share it with anyone.",
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
                    text = "Your Backup Key",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (showNsec) nsec else "••••••••••••••••••••••••••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
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
                        }
                    ) {
                        Text(if (hasCopied) "Copied!" else "Copy")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = { showInfo = !showInfo },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (showInfo) "Hide details" else "How does this work?")
        }

        AnimatedVisibility(
            visible = showInfo,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "About Your Keys",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Ridestr is built on Nostr, an open protocol where your identity is a cryptographic key pair \u2014 not an email or phone number." +
                            "\n\nYour Account ID (npub) is your public key. Anyone can see it." +
                            "\n\nYour Backup Key (nsec) is your private key. It proves you own the account. There\u2019s no central server storing your password, which means no one can reset it for you \u2014 but it also means no one can lock you out." +
                            "\n\nThis is why saving your backup key matters: it\u2019s the only proof of ownership that exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
