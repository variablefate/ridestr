package com.drivestr.app.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.drivestr.app.MainActivity
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideOfferEvent
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.SoundManager
import com.ridestr.common.settings.SettingsManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale

private const val TAG = "RoadflareListenerService"

/**
 * Background service that listens for RoadFlare ride requests.
 *
 * This service runs when the driver enables "RoadFlare Alerts" in settings,
 * allowing them to receive high-priority notifications for RoadFlare requests
 * even when the main app is closed or backgrounded.
 *
 * The service:
 * 1. Maintains Nostr relay connections
 * 2. Subscribes to Kind 3173 events with roadflare tag where driver is in p-tags
 * 3. Shows high-priority notifications with sound when RoadFlare arrives
 * 4. Filters out requests from muted riders
 */
class RoadflareListenerService : Service() {

    companion object {
        private const val ACTION_START = "com.drivestr.app.service.ROADFLARE_START"
        private const val ACTION_STOP = "com.drivestr.app.service.ROADFLARE_STOP"

        const val NOTIFICATION_ID_ROADFLARE_LISTENER = 3001
        const val NOTIFICATION_ID_ROADFLARE_REQUEST = 3002

        // Default sats/USD rate for fare display
        private const val DEFAULT_SATS_PER_DOLLAR = 2000.0

        /**
         * Start the RoadFlare listener service.
         */
        fun start(context: Context) {
            Log.d(TAG, "Starting RoadflareListenerService")
            val intent = Intent(context, RoadflareListenerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop the RoadFlare listener service.
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping RoadflareListenerService")
            val intent = Intent(context, RoadflareListenerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Check if the service should be running based on settings.
         */
        fun isEnabled(context: Context): Boolean {
            val settings = SettingsManager(context)
            return settings.roadflareAlertsEnabled.value
        }
    }

    private var nostrService: NostrService? = null
    private var driverRoadflareRepo: DriverRoadflareRepository? = null
    private var settingsManager: SettingsManager? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var subscriptionJob: Job? = null
    private var subscriptionId: String? = null

    // Track received requests to avoid duplicate notifications
    private val seenRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Cache rider names to avoid repeated lookups
    private val riderNameCache = mutableMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Initialize services
        settingsManager = SettingsManager(this)
        driverRoadflareRepo = DriverRoadflareRepository.getInstance(this)
        nostrService = NostrService(this, settingsManager!!.getEffectiveRelays())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                startListening()
            }
            ACTION_STOP -> {
                Log.d(TAG, "Received STOP action")
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun startListening() {
        // Start foreground with persistent notification
        val notification = NotificationHelper.buildDriverStatusNotification(
            context = this,
            contentIntent = createContentIntent(),
            title = "RoadFlare Alerts Active",
            content = "Listening for ride requests from your followers",
            isHighPriority = false
        )
        startForeground(NOTIFICATION_ID_ROADFLARE_LISTENER, notification)

        // Connect to relays and subscribe
        subscriptionJob = serviceScope.launch {
            try {
                // Connect to relays
                nostrService?.connect()

                // Wait for connection
                delay(2000)

                // Get driver's pubkey
                val driverPubKey = nostrService?.keyManager?.getPubKeyHex()
                if (driverPubKey == null) {
                    Log.e(TAG, "No driver pubkey - cannot subscribe")
                    return@launch
                }

                Log.d(TAG, "Subscribing to RoadFlare requests for driver ${driverPubKey.take(16)}...")

                // Subscribe to Kind 3173 events where we're in p-tags and have roadflare tag
                subscribeToRoadflareRequests(driverPubKey)

            } catch (e: Exception) {
                Log.e(TAG, "Error starting listener", e)
            }
        }
    }

    private fun subscribeToRoadflareRequests(driverPubKey: String) {
        // Get muted pubkeys to filter
        val mutedPubkeys = driverRoadflareRepo?.getMutedPubkeys()?.toSet() ?: emptySet()

        subscriptionId = nostrService?.relayManager?.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf(
                "p" to listOf(driverPubKey),
                "t" to listOf(RideOfferEvent.ROADFLARE_TAG)
            )
        ) { event, relayUrl ->
            // Skip if already seen (atomic check-and-add)
            if (!seenRequests.add(event.id)) return@subscribe

            // Skip if from muted rider
            if (event.pubKey in mutedPubkeys) {
                Log.d(TAG, "Ignoring RoadFlare from muted rider ${event.pubKey.take(8)}")
                return@subscribe
            }

            // Skip expired events
            val expiration = event.tags.find { it.getOrNull(0) == "expiration" }?.getOrNull(1)?.toLongOrNull()
            if (expiration != null && expiration < System.currentTimeMillis() / 1000) {
                Log.d(TAG, "Ignoring expired RoadFlare request")
                return@subscribe
            }

            Log.d(TAG, "Received RoadFlare request from ${event.pubKey.take(8)}")

            // Process the event and show notification
            serviceScope.launch {
                processRoadflareRequest(event)
            }
        }

        Log.d(TAG, "Subscribed with ID: $subscriptionId")
    }

    private suspend fun processRoadflareRequest(event: Event) {
        val riderPubKey = event.pubKey

        // Try to decrypt and parse the fare
        var fareSats: Double? = null
        try {
            val signer = nostrService?.keyManager?.getSigner()
            if (signer != null) {
                val decrypted = signer.nip44Decrypt(event.content, riderPubKey)
                val json = JSONObject(decrypted)
                fareSats = json.optDouble("fare_estimate", Double.NaN)
                if (fareSats.isNaN()) fareSats = null
                Log.d(TAG, "Decrypted RoadFlare offer: fare = $fareSats sats")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt RoadFlare content: ${e.message}")
        }

        // Get or fetch rider's name
        var riderFirstName: String? = riderNameCache[riderPubKey]
        if (riderFirstName == null) {
            // Try to fetch the profile
            try {
                nostrService?.subscribeToProfile(riderPubKey) { profile ->
                    val fullName = profile.displayName ?: profile.name
                    val firstName = fullName?.split(" ")?.firstOrNull()
                    if (!firstName.isNullOrBlank()) {
                        riderNameCache[riderPubKey] = firstName
                        Log.d(TAG, "Fetched rider name: $firstName")
                    }
                }
                // Give a brief moment for profile to come in
                delay(500)
                riderFirstName = riderNameCache[riderPubKey]
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch rider profile: ${e.message}")
            }
        }

        // Build notification message
        showRoadflareNotification(riderFirstName, fareSats, event.id)
    }

    private fun showRoadflareNotification(riderName: String?, fareSats: Double?, eventId: String) {
        // Play alert sound
        SoundManager.playRideRequestAlert(this)

        // Format the notification content
        val displayName = riderName ?: "Someone"
        val content = if (fareSats != null && fareSats > 0) {
            // Convert sats to USD using default rate
            val fareUsd = fareSats / DEFAULT_SATS_PER_DOLLAR
            val formatter = NumberFormat.getCurrencyInstance(Locale.US)
            val fareFormatted = formatter.format(fareUsd)
            "$displayName has broadcasted a RoadFlare for $fareFormatted!"
        } else {
            "$displayName has broadcasted a RoadFlare!"
        }

        // Build notification
        val notification = NotificationHelper.buildDriverStatusNotification(
            context = this,
            contentIntent = createContentIntent(),
            title = "RoadFlare Request!",
            content = content,
            isHighPriority = true
        )

        NotificationHelper.showNotification(
            context = this,
            notificationId = NOTIFICATION_ID_ROADFLARE_REQUEST,
            notification = notification
        )

        Log.d(TAG, "Showed notification: $content")
    }

    private fun stopListening() {
        subscriptionJob?.cancel()
        subscriptionId?.let { id ->
            nostrService?.relayManager?.closeSubscription(id)
        }
        subscriptionId = null
        nostrService?.disconnect()
        seenRequests.clear()
        riderNameCache.clear()
    }

    private fun createContentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", "DRIVE") // Open to Drive tab to see requests
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
