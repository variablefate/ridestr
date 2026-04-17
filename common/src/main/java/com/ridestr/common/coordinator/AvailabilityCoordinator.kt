package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideshareEventKinds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages driver availability broadcasting (Kind 30173) and NIP-09 deletion lifecycle.
 *
 * Responsibilities:
 * - Periodic availability broadcast loop with configurable interval
 * - NIP-09 batch deletion of all published availability events on go-offline / ride-accept
 * - Location-update throttle (time + distance gating) to limit relay spam
 *
 * The coordinator is a plain class that holds no Android context; all network I/O goes
 * through [nostrService]. Pass provider lambdas for mutable runtime values (location,
 * vehicle, mint URL, payment methods) so the broadcast loop always uses fresh state.
 *
 * // TODO(#52): convert constructor injection to @Inject once Hilt migration lands
 */
class AvailabilityCoordinator(
    private val nostrService: NostrService,
    private val walletServiceProvider: () -> com.ridestr.common.payment.WalletService?,
    private val paymentMethodsProvider: () -> List<String>
) {

    companion object {
        private const val TAG = "AvailabilityCoordinator"

        /** Broadcast availability every 5 minutes to stay visible to riders. */
        const val BROADCAST_INTERVAL_MS = 5 * 60 * 1000L

        /** Minimum movement before a location update triggers a re-broadcast. */
        const val MIN_LOCATION_UPDATE_DISTANCE_M = 1_000.0

        /** Minimum time between location-triggered re-broadcasts. */
        const val MIN_LOCATION_UPDATE_INTERVAL_MS = 30 * 1000L
    }

    private val _lastBroadcastTime = MutableStateFlow<Long?>(null)

    /** Timestamp (epoch ms) of the most recent successful Kind 30173 publish, or null. */
    val lastBroadcastTime: StateFlow<Long?> = _lastBroadcastTime.asStateFlow()

    private val publishedEventIds = mutableListOf<String>()

    /** IDs of all Kind 30173 events published in the current online session. */
    val publishedAvailabilityEventIds: List<String> get() = publishedEventIds.toList()

    private var broadcastJob: Job? = null

    // Throttle tracking — updated by startBroadcasting() loop and updateThrottle()
    var lastBroadcastLocation: Location? = null
        private set
    var lastBroadcastTimeMs: Long = 0L
        private set

    // -------------------------------------------------------------------------
    // One-shot publishing
    // -------------------------------------------------------------------------

    /**
     * Publish a single Kind 30173 availability event with the given parameters.
     *
     * @param location Approximate driver location, or null for a locationless event.
     * @param status [com.ridestr.common.nostr.events.DriverAvailabilityEvent.STATUS_AVAILABLE]
     *               or STATUS_OFFLINE.
     * @param vehicle Driver's active vehicle metadata, or null.
     * @param mintUrl Cashu mint URL, or null when wallet is not configured.
     * @param paymentMethods Non-empty list of accepted payment method identifiers.
     * @return Event ID on success, null on failure.
     */
    suspend fun publishAvailability(
        location: Location?,
        status: String,
        vehicle: Vehicle?,
        mintUrl: String?,
        paymentMethods: List<String>
    ): String? = nostrService.broadcastAvailability(
        location = location,
        status = status,
        vehicle = vehicle,
        mintUrl = mintUrl,
        paymentMethods = paymentMethods
    )

    // -------------------------------------------------------------------------
    // Periodic broadcasting loop
    // -------------------------------------------------------------------------

    /**
     * Start the periodic availability broadcast loop.
     *
     * The loop runs every [BROADCAST_INTERVAL_MS] milliseconds, reads the current
     * location and vehicle from the supplied providers, and publishes a new
     * KIND 30173 AVAILABLE event — deleting the previous one first.
     *
     * Calling [startBroadcasting] while a loop is already running cancels the old
     * loop and starts a fresh one (safe to call on location update).
     *
     * @param scope [CoroutineScope] that owns the loop's lifetime (typically viewModelScope).
     * @param locationProvider Returns the driver's current [Location], or null to skip this tick.
     * @param vehicleProvider Returns the driver's active [Vehicle], or null if none selected.
     * @param locationForFirstTick Seed location for the very first broadcast before providers
     *   are populated; usually the location that triggered go-online.
     */
    fun startBroadcasting(
        scope: CoroutineScope,
        locationProvider: () -> Location?,
        vehicleProvider: () -> Vehicle?,
        locationForFirstTick: Location
    ) {
        Log.d(TAG, "startBroadcasting at ${locationForFirstTick.lat},${locationForFirstTick.lon}")
        broadcastJob?.cancel()

        if (lastBroadcastLocation == null) {
            lastBroadcastLocation = locationForFirstTick
            lastBroadcastTimeMs = System.currentTimeMillis()
        }

        broadcastJob = scope.launch {
            var loopCount = 0
            while (isActive) {
                loopCount++
                val currentLocation = locationProvider() ?: locationForFirstTick
                val activeVehicle = vehicleProvider()

                lastBroadcastLocation = currentLocation
                lastBroadcastTimeMs = System.currentTimeMillis()

                // Delete previous event before publishing replacement
                val previousId = publishedEventIds.lastOrNull()
                if (previousId != null) {
                    nostrService.deleteEvent(previousId, "superseded")
                }

                val mintUrl = walletServiceProvider()?.getSavedMintUrl()
                val paymentMethods = paymentMethodsProvider()
                val eventId = publishAvailability(
                    location = currentLocation,
                    status = com.ridestr.common.nostr.events.DriverAvailabilityEvent.STATUS_AVAILABLE,
                    vehicle = activeVehicle,
                    mintUrl = mintUrl,
                    paymentMethods = paymentMethods
                )

                if (eventId != null) {
                    publishedEventIds.add(eventId)
                    _lastBroadcastTime.value = System.currentTimeMillis()
                    Log.d(TAG, "Broadcast loop #$loopCount OK: ${eventId.take(8)} (total: ${publishedEventIds.size})")
                } else {
                    Log.e(TAG, "Broadcast loop #$loopCount FAILED")
                }

                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /** Cancel the broadcast loop without deleting published events from relays. */
    fun stopBroadcasting() {
        broadcastJob?.cancel()
        broadcastJob = null
    }

    // -------------------------------------------------------------------------
    // NIP-09 cleanup
    // -------------------------------------------------------------------------

    /**
     * Request NIP-09 deletion for all Kind 30173 events published in this session.
     *
     * Suspends until the deletion event is confirmed sent (or fails). After this call
     * [publishedAvailabilityEventIds] is cleared regardless of success.
     */
    suspend fun deleteAllAvailabilityEvents() {
        if (publishedEventIds.isEmpty()) {
            Log.d(TAG, "deleteAllAvailabilityEvents: nothing to delete")
            return
        }
        Log.d(TAG, "Deleting ${publishedEventIds.size} availability events")
        val deletionId = nostrService.deleteEvents(
            publishedEventIds.toList(),
            "driver went offline",
            listOf(RideshareEventKinds.DRIVER_AVAILABILITY)
        )
        if (deletionId != null) {
            Log.d(TAG, "Deletion request sent: $deletionId")
        } else {
            Log.w(TAG, "Deletion request failed")
        }
        publishedEventIds.clear()
    }

    // -------------------------------------------------------------------------
    // Location throttle helpers
    // -------------------------------------------------------------------------

    /**
     * Returns true if a new broadcast should be suppressed based on distance / time guards.
     *
     * Both conditions must be met for the update to pass through:
     * - At least [MIN_LOCATION_UPDATE_DISTANCE_M] metres from the last broadcast location.
     * - At least [MIN_LOCATION_UPDATE_INTERVAL_MS] ms since the last broadcast.
     */
    fun shouldThrottle(newLocation: Location): Boolean {
        val last = lastBroadcastLocation ?: return false
        val timeSinceLast = System.currentTimeMillis() - lastBroadcastTimeMs
        val distanceFromLast = calculateDistanceMeters(
            last.lat, last.lon,
            newLocation.lat, newLocation.lon
        )
        val timeOk = timeSinceLast >= MIN_LOCATION_UPDATE_INTERVAL_MS
        val distOk = distanceFromLast >= MIN_LOCATION_UPDATE_DISTANCE_M
        return !(timeOk && distOk)
    }

    /**
     * Record [location] as the most recent broadcast location for future throttle checks.
     * Call this *before* firing a forced (non-throttled) location update.
     */
    fun updateThrottle(location: Location) {
        lastBroadcastLocation = location
        lastBroadcastTimeMs = System.currentTimeMillis()
    }

    /** Reset throttle tracking and clear published event IDs. Call after going offline. */
    fun clearBroadcastState() {
        lastBroadcastLocation = null
        lastBroadcastTimeMs = 0L
        publishedEventIds.clear()
        _lastBroadcastTime.value = null
    }

    // -------------------------------------------------------------------------
    // Internal geometry
    // -------------------------------------------------------------------------

    private fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
