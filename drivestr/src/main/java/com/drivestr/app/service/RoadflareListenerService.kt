package com.drivestr.app.service

import android.app.PendingIntent
import android.app.Service
import dagger.hilt.android.AndroidEntryPoint
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
import com.ridestr.common.nostr.events.RoadflareDriverPingData
import com.ridestr.common.nostr.events.RoadflareDriverPingEvent
import com.drivestr.app.presence.DriverPresenceGate
import com.drivestr.app.presence.DriverPresenceStore
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.notification.SoundManager
import com.ridestr.common.settings.SettingsRepository
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.text.NumberFormat
import java.util.Locale
import javax.inject.Inject

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
@AndroidEntryPoint
class RoadflareListenerService : Service() {

    companion object {
        private const val ACTION_START = "com.drivestr.app.service.ROADFLARE_START"

        const val NOTIFICATION_ID_ROADFLARE_LISTENER = 3001
        const val NOTIFICATION_ID_ROADFLARE_REQUEST = 3002

        // Base ID for driver ping notifications. Each rider gets a unique slot:
        // NOTIFICATION_ID_DRIVER_PING + abs(riderPubKey.hashCode() % 10000).
        // Range: [14001, 24000]. Chosen to avoid collision with follow-notification IDs
        // at NOTIFICATION_ID_FOLLOW_REQUEST + [0, 10000) = [3001, 13000].
        // This mirrors the follow-notification pattern in MainActivity.kt:394.
        const val NOTIFICATION_ID_DRIVER_PING = 14001

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
         * Uses stopService() which is a no-op if not running.
         */
        fun stop(context: Context) {
            Log.d(TAG, "Stopping RoadflareListenerService")
            context.stopService(Intent(context, RoadflareListenerService::class.java))
        }

    }

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var presenceStore: DriverPresenceStore

    private var nostrService: NostrService? = null
    private var driverRoadflareRepo: DriverRoadflareRepository? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var subscriptionJob: Job? = null
    private var subscriptionId: String? = null
    private var pingSubscriptionId: String? = null
    private val pingRateLimiter = DriverPingRateLimiter()

    // Track received requests to avoid duplicate notifications
    private val seenRequests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Cache rider names to avoid repeated lookups
    private val riderNameCache = mutableMapOf<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        runBlocking { settingsRepository.awaitInitialLoad() }

        // Initialize services
        driverRoadflareRepo = DriverRoadflareRepository.getInstance(this)
        nostrService = NostrService.getInstance(this, settingsRepository.getEffectiveRelays())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "Received START action")
                startListening()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(STOP_FOREGROUND_REMOVE)
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

                // Subscribe to Kind 3189 driver pings
                subscribeToDriverPings(driverPubKey)

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

    private fun subscribeToDriverPings(driverPubKey: String) {
        pingSubscriptionId = nostrService?.relayManager?.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_DRIVER_PING),
            tags  = mapOf("p" to listOf(driverPubKey))
        ) { event, _ ->
            // Event-id dedup: seenRequests is shared with Kind 3173 — event IDs are globally unique
            if (!seenRequests.add(event.id)) return@subscribe

            serviceScope.launch {
                processPingEvent(event, driverPubKey)
            }
        }
        Log.d(TAG, "Subscribed to driver pings: $pingSubscriptionId")
    }

    private suspend fun processPingEvent(
        event: Event,
        driverPubKey: String
    ) {
        val signer = nostrService?.keyManager?.getSigner() ?: return
        val roadflareKey = driverRoadflareRepo?.getRoadflareKey() ?: run {
            Log.d(TAG, "No RoadFlare key — cannot validate ping HMAC; dropping")
            return
        }

        // Validate HMAC + expiry + decrypt (null = any failure = silent drop)
        val pingData = RoadflareDriverPingEvent.parseAndDecrypt(
            signer              = signer,
            event               = event,
            driverPubKey        = driverPubKey,
            roadflarePrivKeyHex = roadflareKey.privateKey
        ) ?: return

        // Mute check — after HMAC auth, before notification (spec §1.5).
        // Live fetch from repository so mute changes take effect immediately without
        // requiring a service restart (unlike the Kind 3173 snapshot approach).
        // O(n) in muted-count per event; acceptable up to ~200 muted pubkeys.
        // Revisit with a cached snapshot if drivers report >1000 followers or high muted counts.
        val mutedPubkeys = driverRoadflareRepo?.getMutedPubkeys() ?: emptySet()
        if (event.pubKey in mutedPubkeys) {
            Log.d(TAG, "Discarding authenticated ping from muted rider ${event.pubKey.take(8)}")
            return
        }

        // Suppression ordering (MUST be: mute → presence gate → tryAccept):
        // Rate-limit slots must not be consumed by events that would be suppressed by mute
        // or by driver presence (AVAILABLE / IN_RIDE), so both gates run BEFORE tryAccept().
        val gate = presenceStore.gate.value
        // gate is StateFlow<DriverPresenceGate?> — null = state unknown (app just started), treat as offline → show notification.
        // AVAILABLE / IN_RIDE: suppress — driver is publicly visible, ping is redundant.
        // ROADFLARE_ONLY: do NOT suppress — driver is privately broadcasting to followers only; ping is still relevant.
        if (gate == DriverPresenceGate.AVAILABLE || gate == DriverPresenceGate.IN_RIDE) {
            Log.d(TAG, "Driver is online ($gate) — skipping ping notification")
            return
        }

        // Rate limit + per-rider dedup — called last, after all suppression checks
        if (!pingRateLimiter.tryAccept(event.pubKey)) {
            Log.d(TAG, "Driver ping rate-limited from ${event.pubKey.take(8)}")
            return
        }

        showPingNotification(pingData)
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
        // Check if driver is online (AVAILABLE or IN_RIDE) - DriverViewModel handles these
        // Only show notification when OFFLINE or ROADFLARE_ONLY
        val gate = presenceStore.gate.value
        if (gate == DriverPresenceGate.AVAILABLE || gate == DriverPresenceGate.IN_RIDE) {
            Log.d(TAG, "Driver is online ($gate), skipping notification (DriverViewModel will handle)")
            return
        }

        // Play alert sound (respecting user settings)
        val soundEnabled = settingsRepository.getNotificationSoundEnabled()
        val vibrationEnabled = settingsRepository.getNotificationVibrationEnabled()
        SoundManager.playRideRequestAlert(this, soundEnabled, vibrationEnabled)

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

        // Build notification (dismissible - isOngoing=false)
        val notification = NotificationHelper.buildDriverStatusNotification(
            context = this,
            contentIntent = createContentIntent(),
            title = "RoadFlare Request!",
            content = content,
            isHighPriority = true,
            isOngoing = false  // Allow user to dismiss
        )

        NotificationHelper.showNotification(
            context = this,
            notificationId = NOTIFICATION_ID_ROADFLARE_REQUEST,
            notification = notification
        )

        Log.d(TAG, "Showed notification: $content")
    }

    private fun showPingNotification(pingData: RoadflareDriverPingData) {
        val soundEnabled     = settingsRepository.getNotificationSoundEnabled()
        val vibrationEnabled = settingsRepository.getNotificationVibrationEnabled()
        SoundManager.playRideRequestAlert(this, soundEnabled, vibrationEnabled)

        val notification = NotificationHelper.buildDriverStatusNotification(
            context       = this,
            contentIntent = createContentIntent(),
            title         = "Driver Ping",
            content       = pingData.message,
            isHighPriority = true,
            isOngoing     = false,
            channel       = NotificationHelper.CHANNEL_DRIVER_PING
        )
        // Per-rider stable ID (base + abs(pubkeyHash % 10_000)) — mirrors MainActivity.kt:394.
        // Each rider gets a distinct tray slot so two accepted pings within the 10-min window
        // are visible simultaneously. Birthday paradox: ~130 unique riders for 1% collision
        // probability; acceptable for typical follower counts (<50). Collisions cause one
        // notification to replace another in the tray — no crash, no data loss.
        val notificationId = NOTIFICATION_ID_DRIVER_PING +
            kotlin.math.abs(pingData.riderPubKey.hashCode() % 10000)
        NotificationHelper.showNotification(
            context        = this,
            notificationId = notificationId,
            notification   = notification
        )
        Log.d(TAG, "Showed driver ping notification (id=$notificationId): ${pingData.message.take(60)}")
    }

    private fun stopListening() {
        subscriptionJob?.cancel()
        subscriptionId?.let { id ->
            nostrService?.relayManager?.closeSubscription(id)
        }
        subscriptionId = null
        pingSubscriptionId?.let { id ->
            nostrService?.relayManager?.closeSubscription(id)
        }
        pingSubscriptionId = null
        pingRateLimiter.reset()   // clear slots so a service restart begins from a clean state
        // Don't disconnect the singleton — other components share relay connections
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
