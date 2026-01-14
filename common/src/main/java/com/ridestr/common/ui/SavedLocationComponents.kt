package com.ridestr.common.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.data.SavedLocation

/**
 * Swipeable saved location item with delete revealed on swipe.
 * Pin button is always visible on the card itself.
 */
@Composable
fun SavedLocationItem(
    location: SavedLocation,
    onClick: () -> Unit,
    onPinWithNickname: (String?) -> Unit,
    onUnpin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isRevealed by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var showNicknameDialog by remember { mutableStateOf(false) }

    val animatedOffset by animateFloatAsState(
        targetValue = if (isRevealed) -72f else 0f,
        animationSpec = tween(200),
        label = "swipeOffset"
    )

    val actualOffset = if (isRevealed) animatedOffset else offsetX

    // Nickname dialog
    if (showNicknameDialog) {
        NicknameDialog(
            initialNickname = location.nickname ?: "",
            suggestedNames = listOf("Home", "Work", "Gym"),
            onDismiss = { showNicknameDialog = false },
            onConfirm = { nickname ->
                onPinWithNickname(nickname.ifBlank { null })
                showNicknameDialog = false
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        // Delete background (revealed when swiping left)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.error)
        ) {
            // Delete icon on the right side
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .clickable {
                        onDelete()
                        isRevealed = false
                        offsetX = 0f
                    }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Main content card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .offset(x = actualOffset.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            isRevealed = offsetX < -40f
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!isRevealed) {
                                offsetX = (offsetX + dragAmount).coerceIn(-72f, 0f)
                            }
                        }
                    )
                }
                .clickable {
                    if (isRevealed) {
                        isRevealed = false
                        offsetX = 0f
                    } else {
                        onClick()
                    }
                },
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface  // Better visibility in dark mode
            ),
            shape = RoundedCornerShape(0.dp)  // Outer Box handles clipping - prevents red border bleed
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon based on type
                Icon(
                    imageVector = getLocationIcon(location),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (location.isPinned)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = location.getDisplayText(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = location.getSubtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Pin/Unpin button - subtle, always visible
                IconButton(
                    onClick = {
                        if (location.isPinned) {
                            onUnpin()
                        } else {
                            showNicknameDialog = true
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (location.isPinned) Icons.Default.Star else Icons.Outlined.Star,
                        contentDescription = if (location.isPinned) "Unpin" else "Pin as favorite",
                        modifier = Modifier.size(18.dp),
                        tint = if (location.isPinned)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }

                // Swipe hint - subtle vertical lines
                Column(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .width(4.dp)
                        .height(20.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .height(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f),
                                    RoundedCornerShape(1.dp)
                                )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog to enter a nickname when pinning a location.
 */
@Composable
private fun NicknameDialog(
    initialNickname: String,
    suggestedNames: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var nickname by remember { mutableStateOf(initialNickname) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this place") },
        text = {
            Column {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Nickname (optional)") },
                    placeholder = { Text("e.g., Home, Work, Gym") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Quick picks:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    suggestedNames.forEach { name ->
                        TextButton(
                            onClick = { nickname = name }
                        ) {
                            Text(name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(nickname) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Get the appropriate icon for a saved location based on nickname or type.
 */
private fun getLocationIcon(location: SavedLocation): ImageVector {
    return when {
        location.nickname?.lowercase()?.contains("home") == true -> Icons.Default.Home
        location.nickname?.lowercase()?.contains("work") == true -> Icons.Default.LocationOn
        location.isPinned -> Icons.Default.Star
        else -> Icons.Default.Place
    }
}

/**
 * Section showing pinned favorites.
 */
@Composable
fun FavoritesSection(
    favorites: List<SavedLocation>,
    onLocationSelected: (SavedLocation) -> Unit,
    onUpdateNickname: (String, String?) -> Unit,
    onUnpin: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (favorites.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Star,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Favorites",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        favorites.forEach { location ->
            SavedLocationItem(
                location = location,
                onClick = { onLocationSelected(location) },
                onPinWithNickname = { nickname -> onUpdateNickname(location.id, nickname) },
                onUnpin = { onUnpin(location.id) },
                onDelete = { onDelete(location.id) }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}

/**
 * Section showing recent locations.
 */
@Composable
fun RecentsSection(
    recents: List<SavedLocation>,
    onLocationSelected: (SavedLocation) -> Unit,
    onPinWithNickname: (String, String?) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
    maxItems: Int = 5
) {
    if (recents.isEmpty()) return

    val displayRecents = recents.take(maxItems)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Recent",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        displayRecents.forEach { location ->
            SavedLocationItem(
                location = location,
                onClick = { onLocationSelected(location) },
                onPinWithNickname = { nickname -> onPinWithNickname(location.id, nickname) },
                onUnpin = { }, // Not pinned
                onDelete = { onDelete(location.id) }
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
    }
}
