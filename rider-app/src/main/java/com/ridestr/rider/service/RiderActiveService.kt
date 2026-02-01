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
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    object Cancelled : RiderStatus()
}

/**
 * Stackable alerts that persist until the app comes to foreground.
 * Multiple alerts can be stacked and displayed together in the notification.
 */
sealed class StackableAlert : Serializable {
    data class Chat(val preview: String) : StackableAlert()
    data class DriverAccepted(val driverName: String?) : StackableAlert()
    data class DriverEnRoute(val driverName: String?) : StackableAlert()
    data class Arrived(val driverName: String?) : StackableAlert()
}

/**
 * Foreground service that keeps the rider app alive when backgrounded.
 * Shows a single persistent notification that updates based on ride status.
 * Plays sounds for important events (driver arrived, chat, cancellation).
 *
 * Alerts (chat messages, driver arrived) are stacked and persist until
 * the user brings the app to foreground via clearAlerts().
 */
class RiderActiveService : Service() {

    companion object {
        private const val ACTION_START_SEARCHING = "com.ridestr.rider.service.START_SEARCHING"
        private const val ACTION_UPDATE_STATUS = "com.ridestr.rider.service.UPDATE_STATUS"
        private const val ACTION_ADD_ALERT = "com.ridestr.rider.service.ADD_ALERT"
        private const val ACTION_CLEAR_ALERTS = "com.ridestr.rider.service.CLEAR_ALERTS"
        private const val ACTION_STOP = "com.ridestr.rider.service.STOP"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_ALERT = "alert"

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
         * Update the base notification status.
         * This does NOT clear the alert stack - alerts persist until clearAlerts() is called.
         */
        fun updateStatus(context: Context, status: RiderStatus) {
            Log.d(TAG, "Updating rider status: $status")
            val intent = Intent(context, RiderActiveService::class.java).apply {
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
         * Add a stackable alert (chat message or driver arrived).
         * Alerts persist until clearAlerts() is called (when app comes to foreground).
         */
        fun addAlert(context: Context, alert: StackableAlert) {
            Log.d(TAG, "Adding alert: $alert")
            val intent = Intent(context, RiderActiveService::class.java).apply {
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
            val intent = Intent(context, RiderActiveService::class.java).apply {
                action = ACTION_CLEAR_ALERTS
            }
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

    // Current base status (the underlying ride state)
    private var currentStatus: RiderStatus = RiderStatus.Searching

    // Stacked alerts that persist until cleared (when app comes to foreground)
    private val alertStack = mutableListOf<StackableAlert>()

    // Coroutine scope for any async work
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Settings for sound/vibration preferences
    private var settingsManager: SettingsManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure we're in foreground mode when started via startForegroundService()
        ensureForeground()

        when (intent?.action) {
            ACTION_START_SEARCHING -> {
                Log.d(TAG, "Received START_SEARCHING action")
                currentStatus = RiderStatus.Searching
                alertStack.clear()
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? RiderStatus
                if (status != null) {
                    handleStatusUpdate(status)
                }
            }
            ACTION_ADD_ALERT -> {
                val alert = intent.getSerializableExtra(EXTRA_ALERT) as? StackableAlert
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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun ensureForeground() {
        try {
            val notification = buildNotification()
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
                alertStack.clear()  // Clear alerts when starting fresh search
                updateNotification()
            }
            is RiderStatus.DriverAccepted -> {
                currentStatus = status
                // Play confirmation sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playConfirmationAlert(this, soundEnabled, vibrationEnabled)
                // Add to alert stack so it persists in notification when backgrounded
                alertStack.add(StackableAlert.DriverAccepted(status.driverName))
                updateNotification()
            }
            is RiderStatus.DriverEnRoute -> {
                currentStatus = status
                // Replace accepted alert with en route alert
                alertStack.removeAll { it is StackableAlert.DriverAccepted }
                alertStack.add(StackableAlert.DriverEnRoute(status.driverName))
                updateNotification()
            }
            is RiderStatus.DriverArrived -> {
                // Update base status AND add to alert stack
                currentStatus = status
                // Play driver arrived sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playDriverArrivedAlert(this, soundEnabled, vibrationEnabled)
                // Replace en route alert with arrived alert
                alertStack.removeAll { it is StackableAlert.DriverEnRoute }
                alertStack.add(StackableAlert.Arrived(status.driverName))
                updateNotification()
            }
            is RiderStatus.RideInProgress -> {
                currentStatus = status
                // Clear ride status alerts when ride starts (keep chat messages)
                alertStack.removeAll { it is StackableAlert.Arrived || it is StackableAlert.DriverEnRoute || it is StackableAlert.DriverAccepted }
                updateNotification()
            }
            is RiderStatus.Cancelled -> {
                // Play cancellation sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playCancellationAlert(this, soundEnabled, vibrationEnabled)
                currentStatus = status
                alertStack.clear()
                updateNotification()
            }
        }
    }

    private fun handleAddAlert(alert: StackableAlert) {
        Log.d(TAG, "Adding alert to stack: $alert")

        when (alert) {
            is StackableAlert.Chat -> {
                // Play chat sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playChatMessageAlert(this, soundEnabled, vibrationEnabled)
                // Add to stack (allow multiple chat messages)
                alertStack.add(alert)
            }
            is StackableAlert.DriverAccepted -> {
                // Play confirmation sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playConfirmationAlert(this, soundEnabled, vibrationEnabled)
                // Only add if not already present
                if (alertStack.none { it is StackableAlert.DriverAccepted }) {
                    alertStack.add(alert)
                }
            }
            is StackableAlert.DriverEnRoute -> {
                // Replace accepted with en route
                alertStack.removeAll { it is StackableAlert.DriverAccepted }
                if (alertStack.none { it is StackableAlert.DriverEnRoute }) {
                    alertStack.add(alert)
                }
            }
            is StackableAlert.Arrived -> {
                // Play driver arrived sound (respecting user settings)
                val soundEnabled = settingsManager?.notificationSoundEnabled?.value ?: true
                val vibrationEnabled = settingsManager?.notificationVibrationEnabled?.value ?: true
                SoundManager.playDriverArrivedAlert(this, soundEnabled, vibrationEnabled)
                // Replace en route with arrived
                alertStack.removeAll { it is StackableAlert.DriverEnRoute }
                if (alertStack.none { it is StackableAlert.Arrived }) {
                    alertStack.add(alert)
                }
            }
        }

        updateNotification()
    }

    private fun updateNotification() {
        val notification = buildNotification()
        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_RIDER_ACTIVE,
            notification = notification
        )
    }

    private fun buildNotification(): android.app.Notification {
        val (title, content) = getNotificationText()

        // Use high priority when there are alerts or important status changes
        val isHighPriority = alertStack.isNotEmpty() || when (currentStatus) {
            is RiderStatus.DriverAccepted,
            is RiderStatus.DriverArrived,
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

    private fun getNotificationText(): Pair<String, String> {
        // If we have stacked alerts, show them
        if (alertStack.isNotEmpty()) {
            // Sort alerts: Chat messages first (most visible at top), then ride status alerts
            val sortedAlerts = alertStack.sortedBy { alert ->
                when (alert) {
                    is StackableAlert.Chat -> 0  // Chat at top
                    is StackableAlert.Arrived -> 1
                    is StackableAlert.DriverEnRoute -> 2
                    is StackableAlert.DriverAccepted -> 3
                }
            }

            // Title is based on most important alert type present
            val hasChat = sortedAlerts.any { it is StackableAlert.Chat }
            val hasArrived = sortedAlerts.any { it is StackableAlert.Arrived }
            val hasEnRoute = sortedAlerts.any { it is StackableAlert.DriverEnRoute }
            val hasAccepted = sortedAlerts.any { it is StackableAlert.DriverAccepted }

            val title = when {
                hasChat && hasArrived -> "Driver arrived + Message"
                hasChat && hasEnRoute -> "Driver on the way + Message"
                hasChat && hasAccepted -> "Driver found + Message"
                hasArrived -> "Driver has arrived!"
                hasEnRoute -> "Driver on the way!"
                hasAccepted -> "Driver found!"
                hasChat -> "Message from Driver"
                else -> "Alert"
            }

            // Content shows all stacked alerts (chat first)
            val content = sortedAlerts.joinToString("\n") { alert ->
                when (alert) {
                    is StackableAlert.Chat -> {
                        "Message: ${alert.preview.take(40)}"
                    }
                    is StackableAlert.Arrived -> {
                        val name = alert.driverName ?: "Your driver"
                        "$name is waiting at pickup"
                    }
                    is StackableAlert.DriverEnRoute -> {
                        val name = alert.driverName ?: "Your driver"
                        "$name is heading to you"
                    }
                    is StackableAlert.DriverAccepted -> {
                        val name = alert.driverName ?: "A driver"
                        "$name accepted your ride"
                    }
                }
            }

            return title to content
        }

        // No alerts - show base status
        return getBaseStatusText(currentStatus)
    }

    private fun getBaseStatusText(status: RiderStatus): Pair<String, String> {
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
