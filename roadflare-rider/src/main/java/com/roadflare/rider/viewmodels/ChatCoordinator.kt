package com.roadflare.rider.viewmodels

import android.util.Log
import com.roadflare.common.nostr.NostrService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coordinates in-ride chat between rider and driver.
 *
 * Chat messages are sent as NIP-44 encrypted DMs tagged with the
 * ride's confirmation event ID so both sides can filter them.
 *
 * This is a singleton coordinator — the RiderViewModel delegates
 * chat state management here.
 */
@Singleton
class ChatCoordinator @Inject constructor(
    private val nostrService: NostrService
) {
    companion object {
        private const val TAG = "ChatCoordinator"
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private var subscriptionId: String? = null

    /**
     * Start listening for chat messages on the given ride's confirmation event.
     * Called when a ride is confirmed and the chat channel is established.
     */
    fun startListening(confirmationEventId: String, myPubKey: String) {
        Log.d(TAG, "Starting chat listener for confirmation=$confirmationEventId")
        // Chat subscription will be set up when ride is confirmed.
        // The actual Nostr subscription is managed by the RiderViewModel
        // which routes incoming chat events to addReceivedMessage().
    }

    /**
     * Send a chat message to the driver.
     *
     * Adds the message to local state optimistically, then publishes
     * the encrypted event via NostrService on the IO dispatcher.
     *
     * @param scope CoroutineScope from the caller (e.g., viewModelScope)
     * @param text The message text
     * @param recipientPubKey The driver's public key
     * @param confirmationEventId The ride's confirmation event ID for tagging
     */
    fun sendMessage(
        scope: CoroutineScope,
        text: String,
        recipientPubKey: String,
        confirmationEventId: String
    ) {
        val myPubKey = nostrService.getPubKeyHex() ?: return

        // Optimistically add to local state
        val msg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            senderPubkey = myPubKey,
            text = text,
            timestamp = System.currentTimeMillis(),
            isFromRider = true
        )
        _messages.value = _messages.value + msg
        Log.d(TAG, "Sending chat message to ${recipientPubKey.take(8)}")

        // Publish via Nostr on IO thread
        scope.launch {
            withContext(Dispatchers.IO) {
                nostrService.sendChatMessage(confirmationEventId, recipientPubKey, text)
            }
        }
    }

    /**
     * Add a message received from the driver.
     * Called by the RiderViewModel when a chat event is received from the relay.
     */
    fun addReceivedMessage(senderPubkey: String, text: String, timestamp: Long) {
        val msg = ChatMessage(
            id = java.util.UUID.randomUUID().toString(),
            senderPubkey = senderPubkey,
            text = text,
            timestamp = timestamp,
            isFromRider = false
        )
        _messages.value = _messages.value + msg
        Log.d(TAG, "Received chat message from ${senderPubkey.take(8)}")
    }

    /**
     * Clear all chat messages and close the subscription.
     * Called when a ride ends or is cancelled.
     */
    fun clearMessages() {
        _messages.value = emptyList()
        subscriptionId?.let {
            nostrService.closeSubscription(it)
            Log.d(TAG, "Closed chat subscription")
        }
        subscriptionId = null
    }
}
