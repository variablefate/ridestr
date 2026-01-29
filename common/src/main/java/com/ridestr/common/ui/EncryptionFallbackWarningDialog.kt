package com.ridestr.common.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Non-cancelable warning dialog shown when storage falls back to unencrypted SharedPreferences.
 * This can happen on emulators, rooted devices, devices without hardware-backed keystore,
 * or due to keystore corruption/transient initialization failures.
 *
 * The dialog warns users that their Nostr key, wallet mnemonic, and transaction data
 * are stored in plaintext and could be read by other apps with root access.
 */
@Composable
fun EncryptionFallbackWarningDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-cancelable - user must tap button */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        ),
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text("Security Warning")
        },
        text = {
            Text(
                "Encrypted storage is unavailable on this device. Your Nostr key, wallet mnemonic, " +
                "and transaction data are stored in plaintext.\n\n" +
                "Other apps with root access could read this data.\n\n" +
                "This may occur on emulators, rooted devices, or due to keystore issues."
            )
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("I Understand")
            }
        }
    )
}
