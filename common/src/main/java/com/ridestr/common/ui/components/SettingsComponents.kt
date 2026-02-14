package com.ridestr.common.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A settings row with a switch toggle.
 * Use for boolean on/off settings.
 *
 * @param enabled When false, dims the text and disables the switch (useful for unavailable options)
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    checkedLabel: String? = null,
    uncheckedLabel: String? = null,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (uncheckedLabel != null) {
                Text(
                    text = uncheckedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (!checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
            if (checkedLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = checkedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                )
            }
        }
    }
}

/**
 * A settings row that navigates to another screen.
 * Displays an icon, title, description, and a right-arrow indicator.
 */
@Composable
fun SettingsNavigationRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A settings row for actions that don't navigate (e.g., sync, refresh, clear).
 * Shows a loading indicator when isLoading is true.
 */
@Composable
fun SettingsActionRow(
    title: String,
    description: String,
    icon: ImageVector,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, enabled = !isLoading, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
    }
}
