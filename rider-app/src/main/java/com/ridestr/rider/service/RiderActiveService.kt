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

private const val TAG = "RiderActiveService"

/**
 * Foreground service that keeps the rider app alive when backgrounded.
 * Shows a persistent notification while the rider is searching for drivers
 * or has an active ride in progress.
 */
class RiderActiveService : Service() {

    companion object {
        private const val ACTION_START_SEARCHING = "com.ridestr.rider.service.START_SEARCHING"
        private const val ACTION_START_RIDE = "com.ridestr.rider.service.START_RIDE"
        private const val ACTION_STOP = "com.ridestr.rider.service.STOP"
        private const val ACTION_UPDATE = "com.ridestr.rider.service.UPDATE"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_DRIVER_NAME = "driver_name"

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
         * Start the foreground service in ride mode (driver found).
         */
        fun startRide(context: Context, driverName: String? = null) {
            Log.d(TAG, "Starting RiderActiveService (ride in progress)")
            val intent = Intent(context, RiderActiveService::class.java).apply {
                action = ACTION_START_RIDE
                putExtra(EXTRA_DRIVER_NAME, driverName)
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

        /**
         * Update the notification with a custom message.
         */
        fun updateMessage(context: Context, message: String) {
            Log.d(TAG, "Updating rider service message: $message")
            val intent = Intent(context, RiderActiveService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MESSAGE, message)
            }
            context.startService(intent)
        }
    }

    private var isSearching = true
    private var driverName: String? = null
    private var customMessage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SEARCHING -> {
                Log.d(TAG, "Received START_SEARCHING action")
                isSearching = true
                driverName = null
                customMessage = null
                startForegroundWithNotification()
            }
            ACTION_START_RIDE -> {
                Log.d(TAG, "Received START_RIDE action")
                isSearching = false
                driverName = intent.getStringExtra(EXTRA_DRIVER_NAME)
                customMessage = null
                startForegroundWithNotification()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                customMessage = intent.getStringExtra(EXTRA_MESSAGE)
                Log.d(TAG, "Received UPDATE action, message=$customMessage")
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun startForegroundWithNotification() {
        val notification = if (isSearching) {
            NotificationHelper.buildRiderSearchingNotification(
                context = this,
                contentIntent = createContentIntent()
            )
        } else {
            NotificationHelper.buildRiderRideActiveNotification(
                context = this,
                contentIntent = createContentIntent(),
                driverName = driverName
            )
        }

        try {
            startForeground(NotificationHelper.NOTIFICATION_ID_RIDER_ACTIVE, notification)
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun updateNotification() {
        val notification = if (isSearching) {
            NotificationHelper.buildRiderSearchingNotification(
                context = this,
                contentIntent = createContentIntent(),
                customMessage = customMessage
            )
        } else {
            NotificationHelper.buildRiderRideActiveNotification(
                context = this,
                contentIntent = createContentIntent(),
                driverName = driverName,
                customMessage = customMessage
            )
        }

        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_RIDER_ACTIVE,
            notification = notification
        )
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
