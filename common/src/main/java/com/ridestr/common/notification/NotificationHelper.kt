package com.ridestr.common.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Helper class for creating and managing notifications.
 * Shared between rider and driver apps.
 */
object NotificationHelper {

    // Channel IDs
    const val CHANNEL_ONLINE_STATUS = "online_status"
    const val CHANNEL_RIDE_REQUEST = "ride_request"
    const val CHANNEL_RIDE_UPDATE = "ride_update"
    const val CHANNEL_RIDE_CANCELLED = "ride_cancelled"

    // Notification IDs
    const val NOTIFICATION_ID_ONLINE_STATUS = 1001
    const val NOTIFICATION_ID_RIDE_REQUEST = 1002
    const val NOTIFICATION_ID_RIDE_UPDATE = 1003
    const val NOTIFICATION_ID_RIDE_CANCELLED = 1004
    const val NOTIFICATION_ID_CHAT_MESSAGE = 1005
    const val NOTIFICATION_ID_RIDER_ACTIVE = 2001  // Rider searching/ride in progress

    /**
     * Create all notification channels for the driver app.
     * Should be called once at app startup.
     */
    fun createDriverChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Channel 1: Online status (persistent, low priority)
            val onlineChannel = NotificationChannel(
                CHANNEL_ONLINE_STATUS,
                "Driver Online Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when you are online and available for rides"
                setShowBadge(false)
            }

            // Channel 2: Ride requests (high priority, but sound disabled - SoundManager handles audio)
            val requestChannel = NotificationChannel(
                CHANNEL_RIDE_REQUEST,
                "Ride Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for incoming ride requests"
                // Sound/vibration handled by SoundManager to avoid double alerts
                setSound(null, null)
                enableVibration(false)
            }

            // Channel 3: Ride updates (default priority)
            val updateChannel = NotificationChannel(
                CHANNEL_RIDE_UPDATE,
                "Ride Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates about your current ride"
            }

            // Channel 4: Cancellations (high priority, but sound disabled - SoundManager handles audio)
            val cancelledChannel = NotificationChannel(
                CHANNEL_RIDE_CANCELLED,
                "Ride Cancelled",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a ride is cancelled"
                // Sound/vibration handled by SoundManager to avoid double alerts
                setSound(null, null)
                enableVibration(false)
            }

            notificationManager.createNotificationChannels(
                listOf(onlineChannel, requestChannel, updateChannel, cancelledChannel)
            )
        }
    }

    /**
     * Create all notification channels for the rider app.
     * Should be called once at app startup.
     */
    fun createRiderChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(NotificationManager::class.java)

            // Channel 1: Searching status (persistent, low priority)
            val searchingChannel = NotificationChannel(
                CHANNEL_ONLINE_STATUS,
                "Ride Search Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when you are searching for a driver"
                setShowBadge(false)
            }

            // Channel 2: Driver updates (high priority, but sound disabled - SoundManager handles audio)
            val requestChannel = NotificationChannel(
                CHANNEL_RIDE_REQUEST,
                "Driver Responses",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when a driver accepts your ride"
                // Sound/vibration handled by SoundManager to avoid double alerts
                setSound(null, null)
                enableVibration(false)
            }

            // Channel 3: Ride updates (default priority)
            val updateChannel = NotificationChannel(
                CHANNEL_RIDE_UPDATE,
                "Ride Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Updates about your current ride"
            }

            // Channel 4: Cancellations (high priority, but sound disabled - SoundManager handles audio)
            val cancelledChannel = NotificationChannel(
                CHANNEL_RIDE_CANCELLED,
                "Ride Cancelled",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when your ride is cancelled"
                // Sound/vibration handled by SoundManager to avoid double alerts
                setSound(null, null)
                enableVibration(false)
            }

            notificationManager.createNotificationChannels(
                listOf(searchingChannel, requestChannel, updateChannel, cancelledChannel)
            )
        }
    }

    /**
     * Build a generic driver status notification with custom title and content.
     * Used by the unified notification system for all driver status updates.
     * @param isHighPriority If true, uses high-priority channel for prominent display (e.g., new request, chat)
     * @param isOngoing If true, notification is persistent and cannot be dismissed (default for foreground service)
     * @param channel Override channel selection (null uses default logic based on isHighPriority)
     */
    fun buildDriverStatusNotification(
        context: Context,
        contentIntent: PendingIntent,
        title: String,
        content: String,
        isHighPriority: Boolean = false,
        isOngoing: Boolean = true,
        channel: String? = null
    ): Notification {
        val selectedChannel = channel ?: if (isHighPriority) CHANNEL_RIDE_REQUEST else CHANNEL_ONLINE_STATUS
        val priority = if (isHighPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        return NotificationCompat.Builder(context, selectedChannel)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(isOngoing)
            .setSilent(true)  // Prevent notification sound - SoundManager handles all audio
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(priority)
            .build()
    }

    /**
     * Build a generic rider status notification with custom title and content.
     * Used by the unified notification system for all rider status updates.
     * @param isHighPriority If true, uses high-priority channel for prominent display (e.g., driver arrived, chat)
     * @param isOngoing If true, notification is persistent and cannot be dismissed (default for foreground service)
     * @param channel Override channel selection (null uses default logic based on isHighPriority)
     */
    fun buildRiderStatusNotification(
        context: Context,
        contentIntent: PendingIntent,
        title: String,
        content: String,
        isHighPriority: Boolean = false,
        isOngoing: Boolean = true,
        channel: String? = null
    ): Notification {
        val selectedChannel = channel ?: if (isHighPriority) CHANNEL_RIDE_REQUEST else CHANNEL_ONLINE_STATUS
        val priority = if (isHighPriority) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        return NotificationCompat.Builder(context, selectedChannel)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(isOngoing)
            .setSilent(true)  // Prevent notification sound - SoundManager handles all audio
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(priority)
            .build()
    }

    /**
     * Show a notification.
     */
    fun showNotification(context: Context, notificationId: Int, notification: Notification) {
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted on Android 13+
            android.util.Log.w("NotificationHelper", "Cannot show notification: permission denied")
        }
    }

    /**
     * Cancel a notification.
     */
    fun cancelNotification(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /**
     * Cancel all notifications.
     */
    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /**
     * Check if notification permission is granted (Android 13+).
     * Returns true on older Android versions where permission is implicit.
     */
    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true  // Permission implicit on older versions
        }
    }
}
