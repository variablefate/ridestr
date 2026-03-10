package com.ridestr.common.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.roadflare.FareState
import com.ridestr.common.roadflare.RoadflareDriverUiModel
import com.ridestr.common.roadflare.RoadflareDriverUiModel.DriverStatus

/**
 * Shared driver card composable for RoadFlare driver selection.
 *
 * Adapted from rider-app's inline RoadflareDriverCard (RiderModeScreen.kt:3818-3930).
 * Used by both apps' driver selection screens.
 *
 * Fare display depends on [RoadflareDriverUiModel.fareState]:
 * - CALCULATING: "Fare calculating..." subtitle, no fare amount, card dimmed/disabled
 * - EXACT: formattedFare displayed, no label, card enabled
 * - FALLBACK: formattedFare + "Approx." label, card enabled
 * - ESTIMATED: formattedFare + "Est. fare" subtitle, card enabled (rider-app parity)
 */
@Composable
fun RoadflareDriverCard(
    model: RoadflareDriverUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = model.isDirectSelectable
    val tooFarColor = Color(0xFFE65100)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = when (model.status) {
                                DriverStatus.PENDING_APPROVAL -> MaterialTheme.colorScheme.outline
                                DriverStatus.OFFLINE -> MaterialTheme.colorScheme.outline
                                DriverStatus.TOO_FAR -> tooFarColor
                                DriverStatus.ON_RIDE -> MaterialTheme.colorScheme.tertiary
                                DriverStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                            },
                            shape = RoundedCornerShape(6.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    // Driver name
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Status text
                    Text(
                        text = when (model.status) {
                            DriverStatus.PENDING_APPROVAL -> "Pending approval"
                            DriverStatus.OFFLINE -> "Offline"
                            DriverStatus.TOO_FAR -> "Too far"
                            DriverStatus.ON_RIDE -> "On Ride"
                            DriverStatus.AVAILABLE -> "Available"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when (model.status) {
                            DriverStatus.PENDING_APPROVAL -> MaterialTheme.colorScheme.outline
                            DriverStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
                            DriverStatus.TOO_FAR -> tooFarColor
                            DriverStatus.ON_RIDE -> MaterialTheme.colorScheme.tertiary
                            DriverStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
                        }
                    )

                    // Too-far hint
                    if (model.isTooFar && model.isDirectSelectable) {
                        Text(
                            text = "Excluded from broadcast. Request directly.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Fare calculating indicator
                    if (model.fareState == FareState.CALCULATING) {
                        Text(
                            text = "Fare calculating...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Pickup distance
                    if (model.pickupMiles != null && model.fareState != FareState.CALCULATING) {
                        Text(
                            text = formatPickupDistance(model.pickupMiles),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Fare display (right side)
            when {
                model.fareState == FareState.CALCULATING -> {
                    // No fare shown while calculating
                }
                model.formattedFare != null && (model.status == DriverStatus.AVAILABLE ||
                    model.status == DriverStatus.TOO_FAR) -> {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = model.formattedFare,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        // Fare state label
                        when (model.fareState) {
                            FareState.FALLBACK -> Text(
                                text = "Approx.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FareState.ESTIMATED -> Text(
                                text = "Est. fare",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            else -> {} // EXACT: no label
                        }
                    }
                }
                model.status == DriverStatus.PENDING_APPROVAL -> {
                    Icon(
                        Icons.Default.HourglassEmpty,
                        contentDescription = "Pending",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun formatPickupDistance(miles: Double): String {
    return when {
        miles < 0.1 -> "Very close"
        miles < 10.0 -> String.format("%.1f mi away", miles)
        else -> String.format("%.0f mi away", miles)
    }
}
