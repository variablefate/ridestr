package com.ridestr.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridestr.common.nostr.events.RideshareChatData

/**
 * Bottom sheet for in-ride chat between rider and driver.
 *
 * @param showSheet Whether the sheet should be shown
 * @param onDismiss Called when the sheet is dismissed
 * @param messages List of chat messages
 * @param myPubKey Current user's public key (to identify my messages)
 * @param otherPartyName Name to display for the other party (e.g., "Driver" or "Rider")
 * @param isSending Whether a message is currently being sent
 * @param onSendMessage Called when user sends a message
 * @param unreadCount Number of unread messages (for badge display)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatBottomSheet(
    showSheet: Boolean,
    onDismiss: () -> Unit,
    messages: List<RideshareChatData>,
    myPubKey: String,
    otherPartyName: String,
    isSending: Boolean,
    onSendMessage: (String) -> Unit,
    unreadCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f) // 85% of screen height
            ) {
                // Header
                Surface(
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Chat with $otherPartyName",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )

                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close chat"
                            )
                        }
                    }
                }

                // Chat content
                ChatView(
                    messages = messages,
                    myPubKey = myPubKey,
                    otherPartyName = otherPartyName,
                    isSending = isSending,
                    onSendMessage = onSendMessage,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Floating action button for opening chat.
 * Shows a badge if there are unread messages.
 */
@Composable
fun ChatFab(
    onClick: () -> Unit,
    unreadCount: Int = 0,
    modifier: Modifier = Modifier
) {
    BadgedBox(
        badge = {
            if (unreadCount > 0) {
                Badge {
                    Text(
                        text = if (unreadCount > 99) "99+" else unreadCount.toString()
                    )
                }
            }
        },
        modifier = modifier
    ) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            // Use text-based icon since Chat icon may not be available
            Text(
                text = "\uD83D\uDCAC", // Speech balloon emoji
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}

/**
 * Compact chat button for inline use in ride status cards.
 */
@Composable
fun ChatButton(
    onClick: () -> Unit,
    unreadCount: Int = 0,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier
    ) {
        if (unreadCount > 0) {
            Badge(
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(unreadCount.toString())
            }
        }
        Text("Chat")
    }
}
