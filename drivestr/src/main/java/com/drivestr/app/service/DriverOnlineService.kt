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

private const val TAG = "DriverOnlineService"

/**
 * Foreground service that keeps the driver app alive when backgrounded.
 * Shows a persistent notification while the driver is online.
 */
class DriverOnlineService : Service() {

    companion object {
        private const val ACTION_START = "com.drivestr.app.service.START"
        private const val ACTION_STOP = "com.drivestr.app.service.STOP"
        private const val ACTION_UPDATE = "com.drivestr.app.service.UPDATE"
        private const val EXTRA_REQUEST_COUNT = "request_count"

        /**
         * Start the foreground service.
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
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping DriverOnlineService")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Update the notification with the current request count.
         */
        fun updateRequestCount(context: Context, count: Int) {
            Log.d(TAG, "Updating request count to $count")
            val intent = Intent(context, DriverOnlineService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_REQUEST_COUNT, count)
            }
            context.startService(intent)
        }
    }

    private var requestCount = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                startForegroundWithNotification()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                requestCount = intent.getIntExtra(EXTRA_REQUEST_COUNT, 0)
                Log.d(TAG, "Received UPDATE action, count=$requestCount")
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
        val notification = NotificationHelper.buildDriverOnlineNotification(
            context = this,
            contentIntent = createContentIntent(),
            requestCount = requestCount
        )

        try {
            startForeground(NotificationHelper.NOTIFICATION_ID_ONLINE_STATUS, notification)
            Log.d(TAG, "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service", e)
        }
    }

    private fun updateNotification() {
        val notification = NotificationHelper.buildDriverOnlineNotification(
            context = this,
            contentIntent = createContentIntent(),
            requestCount = requestCount
        )

        NotificationHelper.showNotification(
            context = this,
            notificationId = NotificationHelper.NOTIFICATION_ID_ONLINE_STATUS,
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
