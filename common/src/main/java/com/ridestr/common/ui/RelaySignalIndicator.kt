package com.ridestr.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * WiFi-style signal bars indicator for relay connection status.
 * Shows 3 bars of increasing height, filled based on connected relay count.
 *
 * Visual representation:
 * - 3 connected: ▂▄▆ (all green)
 * - 2 connected: ▂▄░ (first two filled)
 * - 1 connected: ▂░░ (only first filled)
 * - 0 connected: ░░░ (all empty/red)
 *
 * @param connectedCount Number of currently connected relays
 * @param totalRelays Total number of relays configured (used for color only)
 * @param onClick Optional callback when the indicator is clicked (navigates to relay settings)
 * @param modifier Modifier for the composable
 */
@Composable
fun RelaySignalIndicator(
    connectedCount: Int,
    totalRelays: Int,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Determine color based on connection status
    val activeColor = when {
        connectedCount == 0 -> MaterialTheme.colorScheme.error
        connectedCount == 1 -> Color(0xFFFF9800) // Orange
        connectedCount == 2 -> Color(0xFFFFC107) // Amber/Yellow
        else -> MaterialTheme.colorScheme.primary // Green-ish (primary color)
    }

    val inactiveColor = MaterialTheme.colorScheme.surfaceVariant

    val description = "Relay connection: $connectedCount of $totalRelays connected"

    Box(
        modifier = modifier
            .then(
                if (onClick != null) Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = "Open relay settings",
                    onClick = onClick
                ) else Modifier
            )
            .then(
                if (onClick != null) Modifier.semantics {
                    this.contentDescription = description
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(20.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Bar 1 (shortest) - filled if connectedCount >= 1
            SignalBar(
                height = 8.dp,
                isFilled = connectedCount >= 1,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )

            // Bar 2 (medium) - filled if connectedCount >= 2
            SignalBar(
                height = 13.dp,
                isFilled = connectedCount >= 2,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )

            // Bar 3 (tallest) - filled if connectedCount >= 3
            SignalBar(
                height = 18.dp,
                isFilled = connectedCount >= 3,
                activeColor = activeColor,
                inactiveColor = inactiveColor
            )
        }
    }
}

@Composable
private fun SignalBar(
    height: androidx.compose.ui.unit.Dp,
    isFilled: Boolean,
    activeColor: Color,
    inactiveColor: Color
) {
    Box(
        modifier = Modifier
            .width(5.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(if (isFilled) activeColor else inactiveColor)
    )
}
