package com.drivestr.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.drivestr.app.MainActivity
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.Serializable

private const val TAG = "DriverOnlineService"

/**
 * Status types for the driver's unified notification.
 * The notification text and sounds are determined by this status.
 */
sealed class DriverStatus : Serializable {
    data class Available(val requestCount: Int = 0) : DriverStatus()
    data class NewRequest(val count: Int, val fare: String, val distance: String) : DriverStatus()
    data class EnRouteToPickup(val riderName: String?) : DriverStatus()
    data class ArrivedAtPickup(val riderName: String?) : DriverStatus()
    data class RideInProgress(val riderName: String?) : DriverStatus()
    data class ChatReceived(val preview: String, val previousStatus: DriverStatus) : DriverStatus()
    object Cancelled : DriverStatus()
}

/**
 * Foreground service that keeps the driver app alive when backgrounded.
 * Shows a single persistent notification that updates based on ride status.
 * Plays sounds for important events (new request, chat, cancellation).
 */
class DriverOnlineService : Service() {

    companion object {
        private const val ACTION_START = "com.drivestr.app.service.START"
        private const val ACTION_UPDATE_STATUS = "com.drivestr.app.service.UPDATE_STATUS"
        private const val ACTION_STOP = "com.drivestr.app.service.STOP"
        private const val EXTRA_STATUS = "status"

        /**
         * Start the foreground service (driver going online).
         */
        fun start(context: Context) {
            Log.d(TAG, "Starting DriverOnlineService")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Update the notification status and optionally play sounds.
         */
        fun updateStatus(context: Context, status: DriverStatus) {
            Log.d(TAG, "Updating driver status: $status")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            // Use startForegroundService to ensure service is running
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the foreground service (driver going offline).
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping DriverOnlineService")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // Current base status (not chat - chat is temporary overlay)
    private var currentStatus: DriverStatus = DriverStatus.Available(0)

    // Coroutine scope for chat message revert
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var chatRevertJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure we're in foreground mode when started via startForegroundService()
        // This prevents ForegroundServiceDidNotStartInTimeException if service was killed and restarted
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                currentStatus = DriverStatus.Available(0)
                chatRevertJob?.cancel()
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? DriverStatus
                if (status != null) {
                    handleStatusUpdate(status)
                }
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                chatRevertJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        try {
            val notification = buildNotificationForStatus(currentStatus)
            startForeground(NotificationHelper.NOTIFICATION_ID_ONLINE_STATUS, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring foreground", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun handleStatusUpdate(status: DriverStatus) {
        Log.d(TAG, "Handling status update: $status")

        when (status) {
            is DriverStatus.Available -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.NewRequest -> {
                currentStatus = DriverStatus.Available(status.count)
                chatRevertJob?.cancel()
                // Play ride request sound
                SoundManager.playRideRequestAlert(this)
                // Show the new request notification temporarily, then revert to available
                updateNotificationForStatus(status)
                chatRevertJob = serviceScope.launch {
                    delay(5000L)
                    updateNotification()
                }
            }
            is DriverStatus.EnRouteToPickup -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.ArrivedAtPickup -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.RideInProgress -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.ChatReceived -> {
                // Don't update currentStatus - chat is temporary
                handleChatReceived(status)
            }
            is DriverStatus.Cancelled -> {
                // Play cancellation sound
                SoundManager.playCancellationAlert(this)
                updateNotificationForStatus(status)
                // Service will continue - driver is still online
            }
        }
    }

    private fun handleChatReceived(status: DriverStatus.ChatReceived) {
        // Play chat sound
        SoundManager.playChatMessageAlert(this)

        // Show chat preview notification temporarily
        updateNotificationForStatus(status)

        // Schedule revert to previous status after 5 seconds
        chatRevertJob?.cancel()
        chatRevertJob = serviceScope.launch {
            delay(5000L)
            Log.d(TAG, "Reverting from chat preview to: $currentStatus")
            updateNotification()
        }
    }

    private fun updateNotification() {
        updateNotificationForStatus(currentStatus)
    }

    private fun updateNotificationForStatus(status: DriverStatus) {
        val notification = buildNotificationForStatus(status)
        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_ONLINE_STATUS,
            notification = notification
        )
    }

    private fun buildNotificationForStatus(status: DriverStatus): android.app.Notification {
        val (title, content) = getNotificationText(status)

        // Use high priority for important events that need user attention
        val isHighPriority = when (status) {
            is DriverStatus.NewRequest,
            is DriverStatus.ChatReceived,
            is DriverStatus.Cancelled -> true
            else -> false
        }

        return NotificationHelper.buildDriverStatusNotification(
            context = this,
            contentIntent = createContentIntent(),
            title = title,
            content = content,
            isHighPriority = isHighPriority
        )
    }

    private fun getNotificationText(status: DriverStatus): Pair<String, String> {
        return when (status) {
            is DriverStatus.Available -> {
                val count = status.requestCount
                val content = if (count > 0) {
                    "$count ride request${if (count > 1) "s" else ""} waiting"
                } else {
                    "Waiting for ride requests"
                }
                "You are online" to content
            }
            is DriverStatus.NewRequest -> {
                "New ride request!" to "${status.fare} - ${status.distance}"
            }
            is DriverStatus.EnRouteToPickup -> {
                val name = status.riderName ?: "rider"
                "En route to pickup" to "Picking up $name"
            }
            is DriverStatus.ArrivedAtPickup -> {
                val name = status.riderName ?: "rider"
                "Arrived at pickup" to "Waiting for $name"
            }
            is DriverStatus.RideInProgress -> {
                "Ride in progress" to "Heading to destination"
            }
            is DriverStatus.ChatReceived -> {
                "Message from Rider" to status.preview.take(50)
            }
            is DriverStatus.Cancelled -> {
                "Ride cancelled" to "The rider cancelled"
            }
        }
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
