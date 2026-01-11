package com.ridestr.common.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A slide-to-confirm button that requires the user to swipe to complete an action.
 * Helps prevent accidental taps on critical actions.
 *
 * @param text The text to display (e.g., "Slide to drop off rider")
 * @param onConfirm Callback when the slide is completed
 * @param modifier Modifier for the component
 * @param icon The icon to show on the draggable thumb
 * @param backgroundColor The background color of the track
 * @param thumbColor The color of the draggable thumb
 * @param textColor The color of the hint text
 * @param enabled Whether the slider is enabled
 */
@Composable
fun SlideToConfirm(
    text: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
    backgroundColor: Color = MaterialTheme.colorScheme.primaryContainer,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    enabled: Boolean = true
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    // Track width in pixels
    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    // Thumb size
    val thumbSizeDp = 48.dp
    val thumbSizePx = with(density) { thumbSizeDp.toPx() }

    // Current offset of the thumb
    val offsetX = remember { Animatable(0f) }

    // Whether the action has been triggered
    var confirmed by remember { mutableStateOf(false) }

    // Calculate max drag distance
    val maxDragPx = (trackWidthPx - thumbSizePx).coerceAtLeast(0f)

    // Threshold for confirming (80% of the way)
    val confirmThreshold = maxDragPx * 0.8f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(backgroundColor)
            .onSizeChanged { size ->
                trackWidthPx = size.width.toFloat()
            }
    ) {
        // Hint text (centered)
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = text,
                    color = textColor.copy(alpha = if (confirmed) 0.3f else 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                if (!confirmed) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = textColor.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Draggable thumb
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .padding(4.dp)
                .size(thumbSizeDp)
                .clip(RoundedCornerShape(24.dp))
                .background(if (confirmed) MaterialTheme.colorScheme.tertiary else thumbColor)
                .pointerInput(enabled, confirmed) {
                    if (!enabled || confirmed) return@pointerInput

                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offsetX.value >= confirmThreshold) {
                                    // Snap to end and confirm
                                    offsetX.animateTo(maxDragPx, animationSpec = tween(150))
                                    confirmed = true
                                    onConfirm()
                                } else {
                                    // Snap back to start
                                    offsetX.animateTo(0f, animationSpec = tween(300))
                                }
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(0f, maxDragPx)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (confirmed) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Confirmed",
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(24.dp)
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = "Slide to confirm",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
