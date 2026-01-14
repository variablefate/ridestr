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
    object Cancelled : DriverStatus()
}

/**
 * Stackable alerts that persist until the app comes to foreground.
 * Multiple alerts can be stacked and displayed together in the notification.
 */
sealed class DriverStackableAlert : Serializable {
    data class Chat(val preview: String) : DriverStackableAlert()
    data class NewRideRequest(val fare: String, val distance: String) : DriverStackableAlert()
}

/**
 * Foreground service that keeps the driver app alive when backgrounded.
 * Shows a single persistent notification that updates based on ride status.
 * Plays sounds for important events (new request, chat, cancellation).
 *
 * Alerts (chat messages, new requests) are stacked and persist until
 * the user brings the app to foreground via clearAlerts().
 */
class DriverOnlineService : Service() {

    companion object {
        private const val ACTION_START = "com.drivestr.app.service.START"
        private const val ACTION_UPDATE_STATUS = "com.drivestr.app.service.UPDATE_STATUS"
        private const val ACTION_ADD_ALERT = "com.drivestr.app.service.ADD_ALERT"
        private const val ACTION_CLEAR_ALERTS = "com.drivestr.app.service.CLEAR_ALERTS"
        private const val ACTION_STOP = "com.drivestr.app.service.STOP"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_ALERT = "alert"

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
         * Update the base notification status.
         * This does NOT clear the alert stack - alerts persist until clearAlerts() is called.
         */
        fun updateStatus(context: Context, status: DriverStatus) {
            Log.d(TAG, "Updating driver status: $status")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Add a stackable alert (chat message or new ride request).
         * Alerts persist until clearAlerts() is called (when app comes to foreground).
         */
        fun addAlert(context: Context, alert: DriverStackableAlert) {
            Log.d(TAG, "Adding alert: $alert")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_ADD_ALERT
                putExtra(EXTRA_ALERT, alert)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Clear all stacked alerts and revert to base status notification.
         * Call this when the app comes to foreground.
         */
        fun clearAlerts(context: Context) {
            Log.d(TAG, "Clearing alerts")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_CLEAR_ALERTS
            }
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

    // Current base status (the underlying driver state)
    private var currentStatus: DriverStatus = DriverStatus.Available(0)

    // Stacked alerts that persist until cleared (when app comes to foreground)
    private val alertStack = mutableListOf<DriverStackableAlert>()

    // Coroutine scope for any async work
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var newRequestRevertJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure we're in foreground mode when started via startForegroundService()
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                currentStatus = DriverStatus.Available(0)
                alertStack.clear()
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? DriverStatus
                if (status != null) {
                    handleStatusUpdate(status)
                }
            }
            ACTION_ADD_ALERT -> {
                val alert = intent.getSerializableExtra(EXTRA_ALERT) as? DriverStackableAlert
                if (alert != null) {
                    handleAddAlert(alert)
                }
            }
            ACTION_CLEAR_ALERTS -> {
                Log.d(TAG, "Clearing alert stack")
                alertStack.clear()
                updateNotification()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                alertStack.clear()
                newRequestRevertJob?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        try {
            val notification = buildNotification()
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
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.NewRequest -> {
                // Update the request count in the base status
                currentStatus = DriverStatus.Available(status.count)
                // Play ride request sound
                SoundManager.playRideRequestAlert(this)
                // Add to alert stack so it persists
                alertStack.add(DriverStackableAlert.NewRideRequest(status.fare, status.distance))
                updateNotification()
            }
            is DriverStatus.EnRouteToPickup -> {
                currentStatus = status
                // Clear new request alerts when accepting a ride
                alertStack.removeAll { it is DriverStackableAlert.NewRideRequest }
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.ArrivedAtPickup -> {
                currentStatus = status
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.RideInProgress -> {
                currentStatus = status
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.Cancelled -> {
                // Play cancellation sound
                SoundManager.playCancellationAlert(this)
                currentStatus = DriverStatus.Available(0)
                alertStack.clear()
                newRequestRevertJob?.cancel()
                updateNotification()
            }
        }
    }

    private fun handleAddAlert(alert: DriverStackableAlert) {
        Log.d(TAG, "Adding alert to stack: $alert")

        when (alert) {
            is DriverStackableAlert.Chat -> {
                // Play chat sound
                SoundManager.playChatMessageAlert(this)
                // Add to stack (allow multiple chat messages)
                alertStack.add(alert)
            }
            is DriverStackableAlert.NewRideRequest -> {
                // Play ride request sound
                SoundManager.playRideRequestAlert(this)
                // Add to stack
                alertStack.add(alert)
            }
        }

        updateNotification()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_ONLINE_STATUS,
            notification = notification
        )
    }

    private fun buildNotification(): android.app.Notification {
        val (title, content) = getNotificationText()

        // Use high priority when there are alerts or important status changes
        val isHighPriority = alertStack.isNotEmpty() || when (currentStatus) {
            is DriverStatus.NewRequest,
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

    private fun getNotificationText(): Pair<String, String> {
        // If we have stacked alerts, show them
        if (alertStack.isNotEmpty()) {
            // Sort alerts: Chat messages first (most visible at top), then other alerts
            val sortedAlerts = alertStack.sortedBy { alert ->
                when (alert) {
                    is DriverStackableAlert.Chat -> 0  // Chat at top
                    is DriverStackableAlert.NewRideRequest -> 1
                }
            }

            // Title is based on most important alert type present
            val hasChat = sortedAlerts.any { it is DriverStackableAlert.Chat }
            val hasRequest = sortedAlerts.any { it is DriverStackableAlert.NewRideRequest }
            val title = when {
                hasChat && hasRequest -> "New request + Message"
                hasRequest -> "New ride request!"
                hasChat -> "Message from Rider"
                else -> "Alert"
            }

            // Content shows all stacked alerts (chat first)
            val content = sortedAlerts.joinToString("\n") { alert ->
                when (alert) {
                    is DriverStackableAlert.Chat -> {
                        "Message: ${alert.preview.take(40)}"
                    }
                    is DriverStackableAlert.NewRideRequest -> {
                        "${alert.fare} - ${alert.distance}"
                    }
                }
            }

            return title to content
        }

        // No alerts - show base status
        return getBaseStatusText(currentStatus)
    }

    private fun getBaseStatusText(status: DriverStatus): Pair<String, String> {
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
