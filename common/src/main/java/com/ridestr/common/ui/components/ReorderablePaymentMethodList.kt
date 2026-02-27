package com.ridestr.common.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.ridestr.common.nostr.events.PaymentMethod
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import kotlin.math.max

/**
 * A reorderable payment method list with checkboxes and drag handles.
 * Enabled methods appear at the top (draggable), disabled methods at the bottom.
 *
 * Uses Column + verticalScroll (not LazyColumn) for stable gesture handling with ≤20 items.
 * Drag gestures use pointerInput(Unit) + rememberUpdatedState for stable coroutine lifecycle.
 * Visual drag feedback uses graphicsLayer (draw-only, no re-layout).
 *
 * @param allMethods Union of known ROADFLARE_ALTERNATE_METHODS + any unknown stored strings
 * @param enabledMethods Currently enabled methods in priority order
 * @param onOrderChanged Called with the new ordered list when drag ends (not per-swap)
 * @param onMethodToggled Called when a method's checkbox is toggled
 */
@Composable
fun ReorderablePaymentMethodList(
    allMethods: List<String>,
    enabledMethods: List<String>,
    onOrderChanged: (List<String>) -> Unit,
    onMethodToggled: (method: String, enabled: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    val scrollState = rememberScrollState()
    val currentEnabledRef = rememberUpdatedState(enabledMethods)

    // Drag state
    var isDragging by remember { mutableStateOf(false) }
    var dragCancelledExternally by remember { mutableStateOf(false) }
    var draggedMethod by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    var workingOrder by remember { mutableStateOf(enabledMethods) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    var columnHeightPx by remember { mutableIntStateOf(0) }
    var dragStartSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    var autoScrollJob by remember { mutableStateOf<Job?>(null) }

    // Hot zone and speed constants for auto-scroll
    val hotZonePx = with(density) { 40.dp.toPx() }
    val maxSpeedPx = with(density) { 8.dp.toPx() }

    // External mutation detection + sync
    LaunchedEffect(enabledMethods) {
        if (isDragging && enabledMethods != dragStartSnapshot) {
            // External state changed during drag — cancel drag, accept new state
            dragCancelledExternally = true
            autoScrollJob?.cancel()
            isDragging = false
            draggedMethod = null
            dragOffsetPx = 0f
            // onOrderChanged NOT called — partial drag discarded, external truth wins
        }
        workingOrder = enabledMethods // Always sync to external state (no-op if same)
    }

    // Disabled methods (not in enabledMethods)
    val disabledMethods = remember(allMethods, enabledMethods) {
        allMethods.filter { it !in enabledMethods }
    }

    // Shared swap function
    fun checkAndPerformSwap() {
        if (itemHeightPx <= 0) return
        val dm = draggedMethod ?: return
        val idx = workingOrder.indexOf(dm)
        if (idx < 0) return
        if (dragOffsetPx > itemHeightPx / 2f && idx < workingOrder.lastIndex) {
            workingOrder = workingOrder.toMutableList().apply {
                Collections.swap(this, idx, idx + 1)
            }
            dragOffsetPx -= itemHeightPx
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        } else if (dragOffsetPx < -itemHeightPx / 2f && idx > 0) {
            workingOrder = workingOrder.toMutableList().apply {
                Collections.swap(this, idx, idx - 1)
            }
            dragOffsetPx += itemHeightPx
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .onSizeChanged { columnHeightPx = it.height }
    ) {
        // Enabled methods (draggable, in priority order)
        workingOrder.forEachIndexed { _, method ->
            key(method) {
                val isBeingDragged = draggedMethod == method

                Surface(
                    shadowElevation = if (isBeingDragged) 4.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY = if (isBeingDragged) dragOffsetPx else 0f
                            scaleX = if (isBeingDragged) 1.03f else 1f
                            scaleY = if (isBeingDragged) 1.03f else 1f
                        }
                        .zIndex(if (isBeingDragged) 1f else 0f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { itemHeightPx = it.height }
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = true,
                            onCheckedChange = { onMethodToggled(method, false) }
                        )
                        Text(
                            text = PaymentMethod.fromString(method)?.displayName ?: method,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 4.dp)
                        )
                        // 48dp drag handle Box (Material minimum touch target)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .pointerInput(Unit) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            isDragging = true
                                            dragCancelledExternally = false
                                            draggedMethod = method
                                            dragStartSnapshot = currentEnabledRef.value.toList()
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            // Launch auto-scroll coroutine
                                            autoScrollJob?.cancel()
                                            autoScrollJob = coroutineScope.launch {
                                                while (isDragging) {
                                                    val dm = draggedMethod ?: break
                                                    val idx = workingOrder.indexOf(dm)
                                                    if (idx < 0) break
                                                    if (itemHeightPx <= 0) {
                                                        delay(16)
                                                        continue
                                                    }

                                                    val draggedItemTop = idx * itemHeightPx + dragOffsetPx
                                                    val draggedItemBottom = draggedItemTop + itemHeightPx
                                                    val visibleTop = scrollState.value.toFloat()
                                                    val visibleBottom = visibleTop + columnHeightPx

                                                    val downOverlap = max(0f, draggedItemBottom - (visibleBottom - hotZonePx))
                                                    val upOverlap = max(0f, (visibleTop + hotZonePx) - draggedItemTop)

                                                    if (downOverlap > 1f) {
                                                        val speed = (downOverlap / hotZonePx).coerceIn(0f, 1f) * maxSpeedPx
                                                        val consumed = scrollState.scrollBy(speed)
                                                        dragOffsetPx += consumed
                                                        checkAndPerformSwap()
                                                    } else if (upOverlap > 1f) {
                                                        val speed = (upOverlap / hotZonePx).coerceIn(0f, 1f) * maxSpeedPx
                                                        val consumed = scrollState.scrollBy(-speed)
                                                        dragOffsetPx += consumed
                                                        checkAndPerformSwap()
                                                    }

                                                    delay(16) // ~60fps
                                                }
                                            }
                                        },
                                        onDrag = { change, offset ->
                                            dragOffsetPx += offset.y
                                            change.consume() // Prevent parent verticalScroll from competing
                                            checkAndPerformSwap()
                                        },
                                        onDragEnd = {
                                            autoScrollJob?.cancel()
                                            // CRITICAL: clear isDragging BEFORE callback to prevent
                                            // LaunchedEffect(enabledMethods) treating our commit as external mutation
                                            isDragging = false
                                            draggedMethod = null
                                            dragOffsetPx = 0f
                                            // Only persist if NOT externally cancelled AND order changed
                                            if (!dragCancelledExternally && workingOrder != dragStartSnapshot) {
                                                onOrderChanged(workingOrder)
                                            }
                                        },
                                        onDragCancel = {
                                            autoScrollJob?.cancel()
                                            if (!dragCancelledExternally) {
                                                workingOrder = dragStartSnapshot // REVERT (gesture cancel only)
                                            }
                                            // If dragCancelledExternally, workingOrder already set by LaunchedEffect
                                            isDragging = false
                                            draggedMethod = null
                                            dragOffsetPx = 0f
                                        }
                                    )
                                }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DragHandle,
                                contentDescription = "Drag to reorder",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Disabled methods (not draggable)
        disabledMethods.forEach { method ->
            key(method) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Checkbox(
                        checked = false,
                        onCheckedChange = { onMethodToggled(method, true) }
                    )
                    Text(
                        text = PaymentMethod.fromString(method)?.displayName ?: method,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    // Spacer to align with drag handle column
                    Spacer(modifier = Modifier.size(48.dp))
                }
            }
        }
    }
}
