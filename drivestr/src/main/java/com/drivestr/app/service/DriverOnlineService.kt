package com.drivestr.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.drivestr.app.MainActivity
import com.drivestr.app.notification.DriverNotificationTextProvider
import com.ridestr.common.notification.AlertType
import com.ridestr.common.notification.NotificationCoordinator
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.SoundManager
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    object RoadflareOnly : DriverStatus()
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
        private const val ACTION_START_ROADFLARE_ONLY = "com.drivestr.app.service.START_ROADFLARE_ONLY"
        private const val ACTION_UPDATE_STATUS = "com.drivestr.app.service.UPDATE_STATUS"
        private const val ACTION_ADD_ALERT = "com.drivestr.app.service.ADD_ALERT"
        private const val ACTION_CLEAR_ALERTS = "com.drivestr.app.service.CLEAR_ALERTS"
        private const val ACTION_STOP = "com.drivestr.app.service.STOP"
        private const val EXTRA_STATUS = "status"
        private const val EXTRA_ALERT = "alert"

        /**
         * Start the foreground service (driver going online in AVAILABLE mode).
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
         * Start the foreground service in ROADFLARE_ONLY mode.
         * Sets status to ROADFLARE_ONLY immediately, avoiding the race window
         * where start() sets AVAILABLE then updateStatus() sets ROADFLARE_ONLY.
         */
        fun startRoadflareOnly(context: Context) {
            Log.d(TAG, "Starting DriverOnlineService (roadflare-only)")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_START_ROADFLARE_ONLY
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
        fun addAlert(context: Context, alert: AlertType) {
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

    // Notification coordinator handles status text, channel, and alert stacking
    private val coordinator = NotificationCoordinator(DriverNotificationTextProvider())

    // Coroutine scope for any async work
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var newRequestRevertJob: Job? = null

    // Settings for sound/vibration preferences
    private var settingsManager: SettingsManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager.getInstance(this)
        // Clear any stale status from previous process death (handles force-kill)
        // Will be set correctly in onStartCommand
        settingsManager?.setDriverOnlineStatus(null)
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always ensure we're in foreground mode when started via startForegroundService()
        ensureForeground()

        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                coordinator.updateStatus(DriverStatus.Available(0))
                coordinator.clearAlerts()
                newRequestRevertJob?.cancel()
                // Service is authoritative - set status immediately (closes race window)
                settingsManager?.setDriverOnlineStatus("AVAILABLE")
                updateNotification()
            }
            ACTION_START_ROADFLARE_ONLY -> {
                Log.d(TAG, "Received START_ROADFLARE_ONLY action")
                coordinator.updateStatus(DriverStatus.RoadflareOnly)
                coordinator.clearAlerts()
                newRequestRevertJob?.cancel()
                // Set ROADFLARE_ONLY immediately - avoids race window where start() sets
                // AVAILABLE then updateStatus() sets ROADFLARE_ONLY (Finding #1 fix)
                settingsManager?.setDriverOnlineStatus("ROADFLARE_ONLY")
                updateNotification()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? DriverStatus
                if (status != null) {
                    handleStatusUpdate(status)
                    // Map DriverStatus to settings string (service is authoritative)
                    val statusString = when (status) {
                        is DriverStatus.Available -> "AVAILABLE"
                        is DriverStatus.RoadflareOnly -> "ROADFLARE_ONLY"
                        is DriverStatus.EnRouteToPickup,
                        is DriverStatus.ArrivedAtPickup,
                        is DriverStatus.RideInProgress -> "IN_RIDE"
                        is DriverStatus.NewRequest -> "AVAILABLE"  // Still available, just got a request
                        is DriverStatus.Cancelled -> "AVAILABLE"   // Returns to available after cancel
                    }
                    settingsManager?.setDriverOnlineStatus(statusString)
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
                newRequestRevertJob?.cancel()
                // Clear status before stopping (service is authoritative)
                settingsManager?.setDriverOnlineStatus(null)
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
        // Clear status on service destroy (handles normal stop)
        settingsManager?.setDriverOnlineStatus(null)
        Log.d(TAG, "Service destroyed")
    }

    private fun handleStatusUpdate(status: DriverStatus) {
        Log.d(TAG, "Handling status update: $status")

        when (status) {
            is DriverStatus.Available -> {
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.NewRequest -> {
                // TWO-LAYER: Set Available as base status, add alert for persistence
                // This allows "X requests waiting" in base status + stacked alert for notification
                coordinator.updateStatus(DriverStatus.Available(status.count))
                coordinator.addAlert(AlertType.NewRideRequest(status.fare, status.distance))
                // Play ride request sound (respecting user settings)
                SoundManager.playRideRequestAlert(this, settingsManager)
                updateNotification()
            }
            is DriverStatus.EnRouteToPickup -> {
                // Clear new request alerts when accepting a ride
                coordinator.clearAlertsOfType(AlertType.NewRideRequest::class.java)
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.ArrivedAtPickup -> {
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.RideInProgress -> {
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.Cancelled -> {
                // Play cancellation sound (respecting user settings)
                SoundManager.playCancellationAlert(this, settingsManager)
                // Clear ALL alerts on cancellation
                coordinator.clearAlerts()
                // DON'T reset to Available - keep Cancelled until next action
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
                updateNotification()
            }
            is DriverStatus.RoadflareOnly -> {
                coordinator.updateStatus(status)
                newRequestRevertJob?.cancel()
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
            is AlertType.NewRideRequest -> {
                // Play ride request sound (respecting user settings)
                SoundManager.playRideRequestAlert(this, settingsManager)
            }
            else -> {
                // Other alert types (rider-specific) - just add without sound
            }
        }

        coordinator.addAlert(alert)
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
        // Use coordinator for notification data, fall back if status not yet set
        val data = coordinator.buildNotificationData()

        return if (data != null) {
            NotificationHelper.buildDriverStatusNotification(
                context = this,
                contentIntent = createContentIntent(),
                title = data.title,
                content = data.content,
                isHighPriority = data.isHighPriority,
                channel = data.channel
            )
        } else {
            // Fallback for service startup before first status update
            NotificationHelper.buildDriverStatusNotification(
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
