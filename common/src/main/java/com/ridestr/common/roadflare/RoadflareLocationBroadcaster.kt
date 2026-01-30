package com.ridestr.common.roadflare

import android.location.Location
import android.util.Log
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RoadflareLocation
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles periodic location broadcasting for RoadFlare.
 *
 * When active, broadcasts the driver's location every BROADCAST_INTERVAL_MS
 * to all followers via Kind 30014 events. The location is NIP-44 encrypted
 * to the driver's RoadFlare public key, which followers can decrypt using
 * the shared private key they received via Kind 3186.
 *
 * Broadcasting stops when:
 * - The driver has no RoadFlare key (no followers yet)
 * - stopBroadcasting() is called (driver goes offline/app closed)
 *
 * Usage:
 * ```kotlin
 * val broadcaster = RoadflareLocationBroadcaster(repository, nostrService, signer)
 * broadcaster.startBroadcasting(locationProvider) // Start when app is active
 * broadcaster.stopBroadcasting() // Stop when app is backgrounded
 * broadcaster.setOnRide(true) // Update status when on a ride
 * ```
 */
class RoadflareLocationBroadcaster(
    private val repository: DriverRoadflareRepository,
    private val nostrService: NostrService,
    private val signer: NostrSigner
) {
    companion object {
        private const val TAG = "RoadflareBroadcaster"

        /** Broadcast interval in milliseconds (2 minutes) */
        const val BROADCAST_INTERVAL_MS = 120_000L

        /** Minimum interval between broadcasts (prevent spam) */
        const val MIN_BROADCAST_INTERVAL_MS = 60_000L
    }

    private var broadcastJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private val _lastBroadcastTime = MutableStateFlow<Long?>(null)
    val lastBroadcastTime: StateFlow<Long?> = _lastBroadcastTime.asStateFlow()

    private var isOnRide = false
    private var lastLocation: Location? = null

    /**
     * Start broadcasting location at regular intervals.
     *
     * @param locationProvider Function that returns the current location, or null if unavailable
     */
    fun startBroadcasting(locationProvider: suspend () -> Location?) {
        if (broadcastJob?.isActive == true) {
            Log.d(TAG, "Already broadcasting, ignoring start request")
            return
        }

        Log.d(TAG, "Starting location broadcasting")
        _isBroadcasting.value = true

        broadcastJob = scope.launch {
            while (isActive) {
                try {
                    broadcastIfReady(locationProvider)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during broadcast", e)
                }
                delay(BROADCAST_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop broadcasting location.
     * Call when app is backgrounded or driver goes offline.
     */
    fun stopBroadcasting() {
        Log.d(TAG, "Stopping location broadcasting")
        broadcastJob?.cancel()
        broadcastJob = null
        _isBroadcasting.value = false
    }

    /**
     * Set whether the driver is currently on a ride.
     * This affects the status field in broadcasts.
     */
    fun setOnRide(onRide: Boolean) {
        isOnRide = onRide
        Log.d(TAG, "On ride status: $onRide")
    }

    /**
     * Force an immediate broadcast (e.g., when status changes).
     * Respects minimum interval to prevent spam.
     */
    suspend fun broadcastNow(location: Location?) {
        val lastTime = _lastBroadcastTime.value
        val now = System.currentTimeMillis()

        if (lastTime != null && (now - lastTime) < MIN_BROADCAST_INTERVAL_MS) {
            Log.d(TAG, "Broadcast too recent, skipping immediate broadcast")
            return
        }

        if (location != null) {
            lastLocation = location
        }

        broadcastLocation()
    }

    /**
     * Internal: Check conditions and broadcast if ready.
     */
    private suspend fun broadcastIfReady(locationProvider: suspend () -> Location?) {
        val state = repository.state.value
        Log.d(TAG, "=== BROADCAST CHECK ===")
        Log.d(TAG, "state: ${state != null}")

        // Check if we have a RoadFlare key
        val roadflareKey = state?.roadflareKey
        Log.d(TAG, "roadflareKey: ${roadflareKey != null}, version=${roadflareKey?.version}")
        if (roadflareKey == null) {
            Log.d(TAG, "No RoadFlare key, skipping broadcast")
            return
        }

        // Check if we have any active followers
        val activeFollowers = repository.getActiveFollowerPubkeys()
        Log.d(TAG, "activeFollowers: ${activeFollowers.size}")
        for (pubkey in activeFollowers) {
            Log.d(TAG, "  active: ${pubkey.take(8)}")
        }
        if (activeFollowers.isEmpty()) {
            Log.d(TAG, "No active followers, skipping broadcast")
            return
        }

        // Get current location
        val location = locationProvider()
        if (location == null) {
            Log.d(TAG, "No location available, skipping broadcast")
            return
        }

        lastLocation = location
        broadcastLocation()
    }

    /**
     * Internal: Publish the location event to Nostr.
     */
    private suspend fun broadcastLocation() {
        val state = repository.state.value ?: return
        val roadflareKey = state.roadflareKey ?: return
        val androidLocation = lastLocation ?: return

        // Determine status
        val status = when {
            isOnRide -> RoadflareLocationEvent.Status.ON_RIDE
            else -> RoadflareLocationEvent.Status.ONLINE
        }

        Log.d(TAG, "Broadcasting location: ${androidLocation.latitude}, ${androidLocation.longitude}, status=$status")

        // Create RoadflareLocation object
        val location = RoadflareLocation(
            lat = androidLocation.latitude,
            lon = androidLocation.longitude,
            timestamp = System.currentTimeMillis() / 1000,
            status = status,
            onRide = isOnRide
        )

        try {
            val eventId = nostrService.publishRoadflareLocation(
                signer = signer,
                roadflarePubKey = roadflareKey.publicKey,
                location = location,
                keyVersion = roadflareKey.version
            )

            if (eventId != null) {
                _lastBroadcastTime.value = System.currentTimeMillis()
                repository.updateLastBroadcast()
                Log.d(TAG, "Broadcast successful: $eventId")
            } else {
                Log.w(TAG, "Broadcast failed: no event ID returned")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish location", e)
        }
    }

    /**
     * Clean up resources when no longer needed.
     */
    fun destroy() {
        stopBroadcasting()
        scope.cancel()
    }
}
