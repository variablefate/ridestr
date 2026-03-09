package com.roadflare.rider.service

import android.app.Notification
import android.app.NotificationChannel
import dagger.hilt.android.AndroidEntryPoint
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ridestr.common.settings.SettingsRepository
import javax.inject.Inject
import com.roadflare.rider.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.Serializable

private const val TAG = "RiderActiveService"

// ---------------------------------------------------------------------------
// Notification channel / ID constants
// ---------------------------------------------------------------------------
private const val CHANNEL_ID_ACTIVE = "roadflare_active"
private const val CHANNEL_NAME_ACTIVE = "RoadFlare Active"
private const val CHANNEL_ID_HIGH_PRIORITY = "roadflare_ride_update"
private const val CHANNEL_NAME_HIGH_PRIORITY = "Ride Updates"
private const val NOTIFICATION_ID_RIDER_ACTIVE = 2001

// ---------------------------------------------------------------------------
// Vibration patterns (milliseconds)
// ---------------------------------------------------------------------------
private val VIBRATION_CONFIRMATION = longArrayOf(0, 250, 100, 250)
private val VIBRATION_ALERT = longArrayOf(0, 300, 100, 300, 100, 300)
private val VIBRATION_MESSAGE = longArrayOf(0, 150)

// ---------------------------------------------------------------------------
// Alert types for notification stacking (inlined from AlertType)
// ---------------------------------------------------------------------------
sealed interface RiderAlert : Serializable {
    val sortPriority: Int

    data class Chat(val preview: String) : RiderAlert {
        override val sortPriority = 0
    }

    sealed interface RideStatusAlert : RiderAlert {
        val participantName: String?
    }

    data class Arrived(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 10
    }

    data class EnRoute(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 20
    }

    data class Accepted(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 30
    }
}

// ---------------------------------------------------------------------------
// Status types for the rider's unified notification
// ---------------------------------------------------------------------------
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
@AndroidEntryPoint
class RiderActiveService : Service() {

    companion object {
        private const val ACTION_START_SEARCHING = "com.roadflare.rider.service.START_SEARCHING"
        private const val ACTION_UPDATE_STATUS = "com.roadflare.rider.service.UPDATE_STATUS"
        private const val ACTION_ADD_ALERT = "com.roadflare.rider.service.ADD_ALERT"
        private const val ACTION_CLEAR_ALERTS = "com.roadflare.rider.service.CLEAR_ALERTS"
        private const val ACTION_STOP = "com.roadflare.rider.service.STOP"
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
         * This does NOT clear the alert stack -- alerts persist until clearAlerts() is called.
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
        fun addAlert(context: Context, alert: RiderAlert) {
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

    @Inject lateinit var settingsRepository: SettingsRepository

    // Coroutine scope for async work (reading settings, etc.)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Current ride status
    private var currentStatus: RiderStatus? = null

    // Alert stack -- persists until the user brings the app to foreground
    private val alertStack = mutableListOf<RiderAlert>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        kotlinx.coroutines.runBlocking { settingsRepository.awaitInitialLoad() }
        ensureNotificationChannels()
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
                @Suppress("DEPRECATION")
                val status = intent.getSerializableExtra(EXTRA_STATUS) as? RiderStatus
                if (status != null) {
                    handleStatusUpdate(status)
                }
            }
            ACTION_ADD_ALERT -> {
                @Suppress("DEPRECATION")
                val alert = intent.getSerializableExtra(EXTRA_ALERT) as? RiderAlert
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

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    // -----------------------------------------------------------------------
    // Status handling
    // -----------------------------------------------------------------------

    private fun handleStatusUpdate(status: RiderStatus) {
        Log.d(TAG, "Handling status update: $status")

        when (status) {
            is RiderStatus.Searching -> {
                alertStack.clear()
                currentStatus = status
                updateNotification()
            }
            is RiderStatus.DriverAccepted -> {
                playSoundAndVibrate(VIBRATION_CONFIRMATION)
                addAlertInternal(RiderAlert.Accepted(status.driverName))
                currentStatus = status
                updateNotification()
            }
            is RiderStatus.DriverEnRoute -> {
                addAlertInternal(RiderAlert.EnRoute(status.driverName))
                currentStatus = status
                updateNotification()
            }
            is RiderStatus.DriverArrived -> {
                playSoundAndVibrate(VIBRATION_CONFIRMATION)
                addAlertInternal(RiderAlert.Arrived(status.driverName))
                currentStatus = status
                updateNotification()
            }
            is RiderStatus.RideInProgress -> {
                alertStack.removeAll { it is RiderAlert.Arrived }
                alertStack.removeAll { it is RiderAlert.EnRoute }
                alertStack.removeAll { it is RiderAlert.Accepted }
                currentStatus = status
                updateNotification()
            }
            is RiderStatus.Cancelled -> {
                playSoundAndVibrate(VIBRATION_ALERT)
                alertStack.clear()
                currentStatus = status
                updateNotification()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Alert handling
    // -----------------------------------------------------------------------

    private fun handleAddAlert(alert: RiderAlert) {
        Log.d(TAG, "Adding alert to stack: $alert")

        when (alert) {
            is RiderAlert.Chat -> playSoundAndVibrate(VIBRATION_MESSAGE)
            is RiderAlert.Accepted -> playSoundAndVibrate(VIBRATION_CONFIRMATION)
            is RiderAlert.Arrived -> playSoundAndVibrate(VIBRATION_CONFIRMATION)
            is RiderAlert.EnRoute -> { /* No sound for transitional state */ }
        }

        addAlertInternal(alert)
        updateNotification()
    }

    /**
     * Add an alert to the stack with dedup and status-clearing logic.
     */
    private fun addAlertInternal(alert: RiderAlert) {
        when (alert) {
            is RiderAlert.Arrived -> alertStack.removeAll { it is RiderAlert.EnRoute }
            is RiderAlert.EnRoute -> alertStack.removeAll { it is RiderAlert.Accepted }
            else -> { /* allow duplicates for Chat */ }
        }

        if (alert is RiderAlert.RideStatusAlert) {
            if (alertStack.none { it::class == alert::class }) {
                alertStack.add(alert)
            }
        } else {
            alertStack.add(alert)
        }
    }

    // -----------------------------------------------------------------------
    // Notification building (inline -- replaces NotificationHelper +
    // NotificationCoordinator + RiderNotificationTextProvider)
    // -----------------------------------------------------------------------

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            val activeChannel = NotificationChannel(
                CHANNEL_ID_ACTIVE,
                CHANNEL_NAME_ACTIVE,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when RoadFlare is active"
                setShowBadge(false)
            }

            val highPriorityChannel = NotificationChannel(
                CHANNEL_ID_HIGH_PRIORITY,
                CHANNEL_NAME_HIGH_PRIORITY,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Important ride status updates"
                // Sound / vibration handled programmatically to avoid double alerts
                setSound(null, null)
                enableVibration(false)
            }

            nm.createNotificationChannels(listOf(activeChannel, highPriorityChannel))
        }
    }

    private fun ensureForeground() {
        try {
            val notification = buildNotification()
            startForeground(NOTIFICATION_ID_RIDER_ACTIVE, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring foreground", e)
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_RIDER_ACTIVE, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot show notification: permission denied")
        }
    }

    private fun buildNotification(): Notification {
        val status = currentStatus
        val alerts = alertStack.toList()

        val (title, content, highPriority) = if (status == null) {
            Triple("RoadFlare is active", "Starting...", false)
        } else if (alerts.isEmpty()) {
            val (t, c) = getBaseStatusText(status)
            Triple(t, c, isHighPriority(status, alerts))
        } else {
            val sorted = alerts.sortedBy { it.sortPriority }
            val t = getCombinedTitle(sorted)
            val c = sorted.joinToString("\n") { formatAlert(it) }
            Triple(t, c, isHighPriority(status, alerts))
        }

        val channelId = if (highPriority) CHANNEL_ID_HIGH_PRIORITY else CHANNEL_ID_ACTIVE
        val priority = if (highPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setSilent(true) // Sound handled programmatically
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(createContentIntent())
            .setPriority(priority)
            .build()
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

    // -----------------------------------------------------------------------
    // Notification text (inlined from RiderNotificationTextProvider)
    // -----------------------------------------------------------------------

    private fun getBaseStatusText(status: RiderStatus): Pair<String, String> = when (status) {
        is RiderStatus.Searching -> "Looking for a ride" to "Searching for drivers..."
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
        is RiderStatus.RideInProgress -> "Ride in progress" to "On the way to destination"
        is RiderStatus.Cancelled -> "Ride cancelled" to "Your ride was cancelled"
    }

    private fun formatAlert(alert: RiderAlert): String = when (alert) {
        is RiderAlert.Chat -> "Message: ${alert.preview.take(40)}"
        is RiderAlert.Arrived -> "${alert.participantName ?: "Your driver"} is waiting at pickup"
        is RiderAlert.EnRoute -> "${alert.participantName ?: "Your driver"} is heading to you"
        is RiderAlert.Accepted -> "${alert.participantName ?: "A driver"} accepted your ride"
    }

    private fun getCombinedTitle(alerts: List<RiderAlert>): String {
        val hasChat = alerts.any { it is RiderAlert.Chat }
        val hasArrived = alerts.any { it is RiderAlert.Arrived }
        val hasEnRoute = alerts.any { it is RiderAlert.EnRoute }
        val hasAccepted = alerts.any { it is RiderAlert.Accepted }

        return when {
            hasChat && hasArrived -> "Driver arrived + Message"
            hasChat && hasEnRoute -> "Driver on the way + Message"
            hasChat && hasAccepted -> "Driver found + Message"
            hasArrived -> "Driver has arrived!"
            hasEnRoute -> "Driver on the way!"
            hasAccepted -> "Driver found!"
            hasChat -> "Message from Driver"
            else -> "Alert"
        }
    }

    private fun isHighPriority(status: RiderStatus, alerts: List<RiderAlert>): Boolean {
        return alerts.isNotEmpty() || when (status) {
            is RiderStatus.DriverAccepted,
            is RiderStatus.DriverArrived,
            is RiderStatus.Cancelled -> true
            else -> false
        }
    }

    // -----------------------------------------------------------------------
    // Sound & vibration (inline -- replaces SoundManager)
    // Reads settings from SettingsRepository via coroutine snapshot.
    // -----------------------------------------------------------------------

    /**
     * Play the default notification sound and vibrate, respecting user settings
     * and the device ringer mode.
     */
    private fun playSoundAndVibrate(vibrationPattern: LongArray) {
        val soundEnabled = settingsRepository.getNotificationSoundEnabled()
        val vibrationEnabled = settingsRepository.getNotificationVibrationEnabled()

        if (soundEnabled) playNotificationSoundIfAllowed()
        if (vibrationEnabled) vibrateIfAllowed(vibrationPattern)
    }

    private fun playNotificationSoundIfAllowed() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            Log.d(TAG, "Skipping sound -- ringer mode: $ringerMode")
            return
        }

        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val ringtone = RingtoneManager.getRingtone(this, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ringtone.play()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing notification sound", e)
        }
    }

    private fun vibrateIfAllowed(pattern: LongArray) {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val ringerMode = audioManager?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL

        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            Log.d(TAG, "Skipping vibration -- ringer mode is silent")
            return
        }

        try {
            val vibrator = getVibrator() ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering vibration", e)
        }
    }

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
