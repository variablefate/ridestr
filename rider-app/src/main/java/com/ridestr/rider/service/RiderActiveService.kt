package com.ridestr.rider.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ridestr.rider.MainActivity
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

private const val TAG = "RiderActiveService"

/**
 * Status types for the rider's unified notification.
 * The notification text and sounds are determined by this status.
 */
sealed class RiderStatus : Serializable {
    object Searching : RiderStatus()
    data class DriverAccepted(val driverName: String?) : RiderStatus()
    data class DriverEnRoute(val driverName: String?) : RiderStatus()
    data class DriverArrived(val driverName: String?) : RiderStatus()
    data class RideInProgress(val driverName: String?) : RiderStatus()
    data class ChatReceived(val preview: String, val previousStatus: RiderStatus) : RiderStatus()
    object Cancelled : RiderStatus()
}

/**
 * Foreground service that keeps the rider app alive when backgrounded.
 * Shows a single persistent notification that updates based on ride status.
 * Plays sounds for important events (driver arrived, chat, cancellation).
 */
class RiderActiveService : Service() {

    companion object {
        private const val ACTION_START_SEARCHING = "com.ridestr.rider.service.START_SEARCHING"
        private const val ACTION_UPDATE_STATUS = "com.ridestr.rider.service.UPDATE_STATUS"
        private const val ACTION_STOP = "com.ridestr.rider.service.STOP"
        private const val EXTRA_STATUS = "status"

        /**
         * Start the foreground service in searching mode.
         */
        fun startSearching(context: Context) {
            Log.d(TAG, "Starting RiderActiveService (searching)")
            val intent = Intent(context, RiderActiveService::class.java).apply {
                action = ACTION_START_SEARCHING
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
        fun updateStatus(context: Context, status: RiderStatus) {
            Log.d(TAG, "Updating rider status: $status")
            val intent = Intent(context, RiderActiveService::class.java).apply {
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
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping RiderActiveService")
            val intent = Intent(context, RiderActiveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // Current base status (not chat - chat is temporary overlay)
    private var currentStatus: RiderStatus = RiderStatus.Searching

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
            ACTION_START_SEARCHING -> {
                Log.d(TAG, "Received START_SEARCHING action")
                currentStatus = RiderStatus.Searching
                chatRevertJob?.cancel()
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? RiderStatus
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
            startForeground(NotificationHelper.NOTIFICATION_ID_RIDER_ACTIVE, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring foreground", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun handleStatusUpdate(status: RiderStatus) {
        Log.d(TAG, "Handling status update: $status")

        when (status) {
            is RiderStatus.Searching -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is RiderStatus.DriverAccepted -> {
                currentStatus = status
                chatRevertJob?.cancel()
                // Play confirmation sound
                SoundManager.playConfirmationAlert(this)
                updateNotification()
            }
            is RiderStatus.DriverEnRoute -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is RiderStatus.DriverArrived -> {
                currentStatus = status
                chatRevertJob?.cancel()
                // Play driver arrived sound
                SoundManager.playDriverArrivedAlert(this)
                updateNotification()
            }
            is RiderStatus.RideInProgress -> {
                currentStatus = status
                chatRevertJob?.cancel()
                updateNotification()
            }
            is RiderStatus.ChatReceived -> {
                // Don't update currentStatus - chat is temporary
                handleChatReceived(status)
            }
            is RiderStatus.Cancelled -> {
                // Play cancellation sound
                SoundManager.playCancellationAlert(this)
                updateNotificationForStatus(status)
                // Service will be stopped by ViewModel
            }
        }
    }

    private fun handleChatReceived(status: RiderStatus.ChatReceived) {
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

    private fun updateNotificationForStatus(status: RiderStatus) {
        val notification = buildNotificationForStatus(status)
        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_RIDER_ACTIVE,
            notification = notification
        )
    }

    private fun buildNotificationForStatus(status: RiderStatus): android.app.Notification {
        val (title, content) = getNotificationText(status)

        // Use high priority for important events that need user attention
        val isHighPriority = when (status) {
            is RiderStatus.DriverAccepted,
            is RiderStatus.DriverArrived,
            is RiderStatus.ChatReceived,
            is RiderStatus.Cancelled -> true
            else -> false
        }

        return NotificationHelper.buildRiderStatusNotification(
            context = this,
            contentIntent = createContentIntent(),
            title = title,
            content = content,
            isHighPriority = isHighPriority
        )
    }

    private fun getNotificationText(status: RiderStatus): Pair<String, String> {
        return when (status) {
            is RiderStatus.Searching -> {
                "Looking for a ride" to "Searching for drivers..."
            }
            is RiderStatus.DriverAccepted -> {
                val name = status.driverName ?: "A driver"
                "Driver found!" to "$name accepted your ride"
            }
            is RiderStatus.DriverEnRoute -> {
                val name = status.driverName ?: "Your driver"
                "Driver on the way" to "$name is heading to you"
            }
            is RiderStatus.DriverArrived -> {
                val name = status.driverName ?: "Your driver"
                "Driver has arrived!" to "$name is waiting at pickup"
            }
            is RiderStatus.RideInProgress -> {
                "Ride in progress" to "On the way to destination"
            }
            is RiderStatus.ChatReceived -> {
                "Message from Driver" to status.preview.take(50)
            }
            is RiderStatus.Cancelled -> {
                "Ride cancelled" to "Your ride was cancelled"
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
