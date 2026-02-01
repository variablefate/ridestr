package com.ridestr.rider.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ridestr.common.notification.AlertType
import com.ridestr.common.notification.NotificationCoordinator
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.SoundManager
import com.ridestr.common.settings.SettingsManager
import com.ridestr.rider.MainActivity
import com.ridestr.rider.notification.RiderNotificationTextProvider
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
        fun addAlert(context: Context, alert: AlertType) {
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

    // Notification coordinator handles status text, channel, and alert stacking
    private val coordinator = NotificationCoordinator(RiderNotificationTextProvider())

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
                coordinator.updateStatus(RiderStatus.Searching)
                coordinator.clearAlerts()
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? RiderStatus
                if (status != null) {
                    handleStatusUpdate(status)
                }
            }
            ACTION_ADD_ALERT -> {
                val alert = intent.getSerializableExtra(EXTRA_ALERT) as? AlertType
                if (alert != null) {
                    handleAddAlert(alert)
                }
            }
            ACTION_CLEAR_ALERTS -> {
                Log.d(TAG, "Clearing alert stack")
                coordinator.clearAlerts()
                updateNotification()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                coordinator.clearAlerts()
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
                // Clear alerts when starting fresh search
                coordinator.clearAlerts()
                coordinator.updateStatus(status)
                updateNotification()
            }
            is RiderStatus.DriverAccepted -> {
                // Play confirmation sound (respecting user settings)
                SoundManager.playConfirmationAlert(this, settingsManager)
                // Add to alert stack so it persists in notification when backgrounded
                coordinator.addAlert(AlertType.Accepted(status.driverName))
                coordinator.updateStatus(status)
                updateNotification()
            }
            is RiderStatus.DriverEnRoute -> {
                // coordinator.addAlert clears Accepted when adding EnRoute
                coordinator.addAlert(AlertType.EnRoute(status.driverName))
                coordinator.updateStatus(status)
                updateNotification()
            }
            is RiderStatus.DriverArrived -> {
                // Play driver arrived sound (respecting user settings)
                SoundManager.playDriverArrivedAlert(this, settingsManager)
                // coordinator.addAlert clears EnRoute when adding Arrived
                coordinator.addAlert(AlertType.Arrived(status.driverName))
                coordinator.updateStatus(status)
                updateNotification()
            }
            is RiderStatus.RideInProgress -> {
                // Clear ride status alerts when ride starts (keep chat messages)
                coordinator.clearAlertsOfType(AlertType.Arrived::class.java)
                coordinator.clearAlertsOfType(AlertType.EnRoute::class.java)
                coordinator.clearAlertsOfType(AlertType.Accepted::class.java)
                coordinator.updateStatus(status)
                updateNotification()
            }
            is RiderStatus.Cancelled -> {
                // Play cancellation sound (respecting user settings)
                SoundManager.playCancellationAlert(this, settingsManager)
                // Clear ALL alerts on cancellation
                coordinator.clearAlerts()
                coordinator.updateStatus(status)
                updateNotification()
            }
        }
    }

    private fun handleAddAlert(alert: AlertType) {
        Log.d(TAG, "Adding alert to stack: $alert")

        when (alert) {
            is AlertType.Chat -> {
                // Play chat sound (respecting user settings)
                SoundManager.playChatMessageAlert(this, settingsManager)
            }
            is AlertType.Accepted -> {
                // Play confirmation sound (respecting user settings)
                SoundManager.playConfirmationAlert(this, settingsManager)
            }
            is AlertType.EnRoute -> {
                // No sound for en route - transitional state
            }
            is AlertType.Arrived -> {
                // Play driver arrived sound (respecting user settings)
                SoundManager.playDriverArrivedAlert(this, settingsManager)
            }
            else -> {
                // Other alert types (driver-specific) - just add without sound
            }
        }

        // coordinator.addAlert handles dedup and status-clearing logic
        coordinator.addAlert(alert)
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
        // Use coordinator for notification data, fall back if status not yet set
        val data = coordinator.buildNotificationData()

        return if (data != null) {
            NotificationHelper.buildRiderStatusNotification(
                context = this,
                contentIntent = createContentIntent(),
                title = data.title,
                content = data.content,
                isHighPriority = data.isHighPriority,
                channel = data.channel
            )
        } else {
            // Fallback for service startup before first status update
            NotificationHelper.buildRiderStatusNotification(
                context = this,
                contentIntent = createContentIntent(),
                title = "Starting...",
                content = "",
                isHighPriority = false
            )
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
