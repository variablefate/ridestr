package com.ridestr.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Account bottom sheet for profile, backup, and logout actions.
 *
 * Consolidates the previous 4 header buttons (Key, Person, Settings, Debug)
 * into a cleaner 2-button header (Account, Settings) with this sheet
 * containing Profile, Backup Keys, and Logout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountBottomSheet(
    npub: String?,
    relayStatus: String,
    isConnected: Boolean,
    onEditProfile: () -> Unit,
    onBackupKeys: () -> Unit,
    onAccountSafety: () -> Unit,
    onRelaySettings: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    modifier: Modifier = Modifier
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Identity section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Your Account",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = npub?.let { "${it.take(20)}..." } ?: "Not logged in",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Connection status badge (clickable - opens relay settings)
                Surface(
                    onClick = {
                        onRelaySettings()
                        onDismiss()
                    },
                    color = if (isConnected)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = relayStatus,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (isConnected)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Menu items
            AccountMenuItem(
                icon = Icons.Default.Person,
                title = "Edit Profile",
                subtitle = "Update your display name and photo",
                onClick = {
                    onEditProfile()
                    onDismiss()
                }
            )

            AccountMenuItem(
                icon = Icons.Default.Lock,
                title = "Backup Keys",
                subtitle = "View and copy your Nostr keys",
                onClick = {
                    onBackupKeys()
                    onDismiss()
                }
            )

            AccountMenuItem(
                icon = Icons.Default.Delete,
                title = "Account Safety",
                subtitle = "Delete rideshare data from relays",
                onClick = {
                    onAccountSafety()
                    onDismiss()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Logout - styled as danger action
            AccountMenuItem(
                icon = Icons.AutoMirrored.Filled.ExitToApp,
                title = "Logout",
                subtitle = "Sign out of this account",
                onClick = {
                    onLogout()
                    onDismiss()
                },
                isDanger = true
            )
        }
    }
}

@Composable
private fun AccountMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    isDanger: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isDanger)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isDanger)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
