package com.ridestr.common.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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
     * Build a persistent notification for driver online status.
     * Used as the foreground service notification.
     */
    fun buildDriverOnlineNotification(
        context: Context,
        contentIntent: PendingIntent,
        requestCount: Int = 0
    ): Notification {
        val contentText = if (requestCount > 0) {
            "$requestCount ride request${if (requestCount > 1) "s" else ""} waiting"
        } else {
            "Waiting for ride requests"
        }

        return NotificationCompat.Builder(context, CHANNEL_ONLINE_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle("You are online")
            .setContentText(contentText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Build a notification for a new ride request.
     */
    fun buildRideRequestNotification(
        context: Context,
        contentIntent: PendingIntent,
        fareAmount: String,
        distance: String
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_RIDE_REQUEST)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle("New Ride Request!")
            .setContentText("$fareAmount - $distance away")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }

    /**
     * Build a persistent notification for rider searching status.
     */
    fun buildRiderSearchingNotification(
        context: Context,
        contentIntent: PendingIntent,
        driverCount: Int = 0,
        customMessage: String? = null
    ): Notification {
        val contentText = customMessage ?: if (driverCount > 0) {
            "Searching... $driverCount driver${if (driverCount > 1) "s" else ""} nearby"
        } else {
            "Searching for drivers..."
        }

        return NotificationCompat.Builder(context, CHANNEL_ONLINE_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle("Looking for a ride")
            .setContentText(contentText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Build a persistent notification for an active ride (driver found).
     */
    fun buildRiderRideActiveNotification(
        context: Context,
        contentIntent: PendingIntent,
        driverName: String? = null,
        customMessage: String? = null
    ): Notification {
        val title = if (driverName != null) "Ride with $driverName" else "Ride in progress"
        val contentText = customMessage ?: "Your ride is on the way"

        return NotificationCompat.Builder(context, CHANNEL_ONLINE_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Build a notification when driver accepts the ride.
     */
    fun buildDriverAcceptedNotification(
        context: Context,
        contentIntent: PendingIntent,
        driverName: String?
    ): Notification {
        val title = if (driverName != null) "$driverName accepted!" else "Driver accepted!"

        return NotificationCompat.Builder(context, CHANNEL_RIDE_REQUEST)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText("Your ride has been confirmed")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }

    /**
     * Build a notification when driver arrives at pickup.
     */
    fun buildDriverArrivedNotification(
        context: Context,
        contentIntent: PendingIntent
    ): Notification {
        return NotificationCompat.Builder(context, CHANNEL_RIDE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle("Driver has arrived!")
            .setContentText("Your driver is waiting at the pickup location")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }

    /**
     * Build a notification for ride cancellation.
     */
    fun buildRideCancelledNotification(
        context: Context,
        contentIntent: PendingIntent,
        cancelledBy: String // "rider" or "driver"
    ): Notification {
        val title = if (cancelledBy == "rider") "Ride cancelled by rider" else "Ride cancelled"

        return NotificationCompat.Builder(context, CHANNEL_RIDE_CANCELLED)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText("The ride has been cancelled")
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .build()
    }

    /**
     * Build a notification for chat messages.
     */
    fun buildChatMessageNotification(
        context: Context,
        contentIntent: PendingIntent,
        senderName: String?,
        messagePreview: String
    ): Notification {
        val title = senderName ?: "New message"
        val preview = if (messagePreview.length > 50) {
            messagePreview.take(47) + "..."
        } else {
            messagePreview
        }

        return NotificationCompat.Builder(context, CHANNEL_RIDE_UPDATE)
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO: Use app icon
            .setContentTitle(title)
            .setContentText(preview)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
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
}
