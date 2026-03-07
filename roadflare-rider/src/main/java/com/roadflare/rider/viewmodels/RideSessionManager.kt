package com.roadflare.rider.viewmodels

import android.util.Log
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.state.RideState
import com.ridestr.common.state.RideStateMachine
import com.roadflare.rider.state.RideStage
import androidx.annotation.VisibleForTesting
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Information about a driver who may service a ride.
 */
data class DriverInfo(
    val pubkey: String,
    val displayName: String,
    val vehicleDescription: String? = null,
    val etaMinutes: Int? = null,
    val distanceMiles: Double? = null
)

/**
 * A single chat message in a ride conversation.
 */
data class ChatMessage(
    val id: String,
    val senderPubkey: String,
    val text: String,
    val timestamp: Long,
    val isFromRider: Boolean,
    val isDelivered: Boolean = false
)

/**
 * Represents an active or completed ride session.
 * Immutable data class — updates produce new copies.
 */
data class RideSession(
    val rideId: String,
    val state: RideState,
    val pickupLat: Double,
    val pickupLon: Double,
    val dropoffLat: Double,
    val dropoffLon: Double,
    val fareEstimateUsd: Double,
    val fareEstimateSats: Long? = null,
    val selectedDriver: DriverInfo? = null,
    val pendingDrivers: List<String> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val paymentMethod: PaymentMethod = PaymentMethod.FIAT_CASH,
    val schemaVersion: Int = 1
)

/**
 * Manages the lifecycle of a ride session:
 * - Sends batched RoadFlare offers to nearby drivers
 * - Tracks acceptances and lets rider choose a driver
 * - Confirms rides and cleans up non-chosen offers via NIP-09
 * - Handles cancellation with full event cleanup
 *
 * The RiderViewModel delegates ride-session state management here.
 */
class RideSessionManager(
    private val nostrService: NostrService
) {
    companion object {
        private const val TAG = "RideSessionManager"
        private const val BATCH_SIZE = 3
        private const val BATCH_DELAY_MS = 15_000L

        // Subscription group keys
        const val SUB_GROUP_ACCEPTANCE = "acceptance"
        const val SUB_GROUP_BATCH_ACCEPTANCE = "batch_acceptance"
        const val SUB_GROUP_RIDE_UPDATES = "ride_updates"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stateMachine = RideStateMachine()

    private val _currentRide = MutableStateFlow<RideSession?>(null)
    val currentRide: StateFlow<RideSession?> = _currentRide.asStateFlow()

    private val _rideStage = MutableStateFlow(RideStage.IDLE)
    val rideStage: StateFlow<RideStage> = _rideStage.asStateFlow()

    private val contactedDrivers = mutableMapOf<String, String>() // pubkey -> offerEventId
    private val acceptedDrivers = mutableListOf<DriverInfo>()
    private val acceptanceSubscriptionIds = mutableListOf<String>()
    private var batchJob: Job? = null
    private var hasSentOffers = false
    private var driverNames: Map<String, String> = emptyMap()

    /**
     * Initiate a RoadFlare ride request by sending offers to all followed drivers,
     * batched by proximity (closest first, BATCH_SIZE at a time).
     */
    fun sendRoadflareToAll(
        drivers: List<Pair<String, Double>>, // pubkey, distanceMiles
        driverNames: Map<String, String>,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        fareEstimateSats: Long?,
        paymentMethod: PaymentMethod,
        fiatPaymentMethods: List<String>
    ) {
        val rideId = java.util.UUID.randomUUID().toString()
        _currentRide.value = RideSession(
            rideId = rideId,
            state = RideState.CREATED,
            pickupLat = pickup.lat,
            pickupLon = pickup.lon,
            dropoffLat = destination.lat,
            dropoffLon = destination.lon,
            fareEstimateUsd = fareEstimate,
            fareEstimateSats = fareEstimateSats,
            pendingDrivers = drivers.map { it.first },
            paymentMethod = paymentMethod
        )
        hasSentOffers = true
        this.driverNames = driverNames
        updateStage()

        Log.d(TAG, "Sending RoadFlare to ${drivers.size} drivers, batch size=$BATCH_SIZE")
        val sorted = drivers.sortedBy { it.second }
        batchJob = scope.launch {
            sendRoadflareBatches(
                sorted, driverNames, pickup, destination,
                fareEstimate, paymentMethod.value, fiatPaymentMethods
            )
        }
    }

    /**
     * Send offers in chunks of BATCH_SIZE, waiting BATCH_DELAY_MS between batches
     * to give closer drivers time to respond before contacting farther ones.
     */
    private suspend fun sendRoadflareBatches(
        sortedDrivers: List<Pair<String, Double>>,
        driverNames: Map<String, String>,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        paymentMethod: String,
        fiatPaymentMethods: List<String>
    ) {
        val chunks = sortedDrivers.chunked(BATCH_SIZE)
        for ((index, chunk) in chunks.withIndex()) {
            if (!coroutineContext.isActive) break

            for ((pubkey, distance) in chunk) {
                Log.d(TAG, "Sending offer to ${driverNames[pubkey] ?: pubkey.take(8)} (${String.format("%.1f", distance)} mi)")
                val eventId = nostrService.sendRideOffer(
                    driverPubKey = pubkey,
                    pickup = pickup,
                    destination = destination,
                    fareEstimate = fareEstimate,
                    paymentMethod = paymentMethod,
                    isRoadflare = true,
                    fiatPaymentMethods = fiatPaymentMethods
                )
                if (eventId != null) {
                    contactedDrivers[pubkey] = eventId
                    // Subscribe to acceptances for this offer
                    val subId = nostrService.subscribeToAcceptancesForOffer(eventId) { acceptance ->
                        onAcceptanceReceived(acceptance)
                    }
                    acceptanceSubscriptionIds.add(subId)
                }
            }

            if (index < chunks.size - 1) {
                Log.d(TAG, "Batch ${index + 1}/${chunks.size} sent, waiting ${BATCH_DELAY_MS}ms before next batch")
                delay(BATCH_DELAY_MS)
            }
        }
        Log.d(TAG, "All ${sortedDrivers.size} offers sent across ${chunks.size} batch(es)")
    }

    /**
     * Called when a driver acceptance event arrives from the relay.
     * Builds DriverInfo from the acceptance data and adds to the accepted list.
     */
    private fun onAcceptanceReceived(acceptance: RideAcceptanceData) {
        Log.d(TAG, "Acceptance received from ${acceptance.driverPubKey}")
        val driverInfo = DriverInfo(
            pubkey = acceptance.driverPubKey,
            displayName = driverNames[acceptance.driverPubKey] ?: acceptance.driverPubKey.take(8)
        )
        handleBatchAcceptance(driverInfo, acceptance)
    }

    /** The most recent acceptance data for each driver, keyed by pubkey. */
    private val acceptanceDataMap = mutableMapOf<String, RideAcceptanceData>()

    /**
     * Handle a driver acceptance during batch sending.
     * Cancels remaining batches — first acceptance wins.
     */
    fun handleBatchAcceptance(driverInfo: DriverInfo, acceptance: RideAcceptanceData? = null) {
        Log.d(TAG, "Driver accepted: ${driverInfo.displayName}")
        acceptedDrivers.add(driverInfo)
        acceptance?.let { acceptanceDataMap[driverInfo.pubkey] = it }
        batchJob?.cancel()
        updateStage()
    }

    /**
     * Rider confirms a specific driver for the ride.
     * Sends the Nostr confirmation event (Kind 3175) and NIP-09 deletion for non-chosen offers.
     */
    fun confirmDriver(driver: DriverInfo) {
        Log.d(TAG, "Confirming driver: ${driver.displayName}")
        val ride = _currentRide.value ?: return
        val acceptance = acceptanceDataMap[driver.pubkey]

        scope.launch {
            // Send the Nostr ride confirmation event (Kind 3175)
            if (acceptance != null) {
                val precisePickup = Location(ride.pickupLat, ride.pickupLon)
                val confirmationEventId = nostrService.confirmRide(
                    acceptance = acceptance,
                    precisePickup = precisePickup
                )
                if (confirmationEventId != null) {
                    Log.d(TAG, "Ride confirmed on Nostr: $confirmationEventId")
                } else {
                    Log.e(TAG, "Failed to send Nostr confirmation event")
                }
            } else {
                Log.w(TAG, "No acceptance data for driver ${driver.pubkey}, skipping Nostr confirmation")
            }

            // NIP-09 delete offers to non-chosen drivers
            val nonChosenOffers = contactedDrivers
                .filter { it.key != driver.pubkey }
                .values.toList()
            if (nonChosenOffers.isNotEmpty()) {
                Log.d(TAG, "Deleting ${nonChosenOffers.size} non-chosen offer(s)")
                nostrService.deleteEvents(nonChosenOffers, "ride confirmed with different driver")
            }

            // Close acceptance subscriptions — no longer needed
            acceptanceSubscriptionIds.forEach { nostrService.closeSubscription(it) }
            acceptanceSubscriptionIds.clear()
        }

        _currentRide.value = ride.copy(
            state = RideState.CONFIRMED,
            selectedDriver = driver
        )
        updateStage()
    }

    /**
     * Cancel the current ride. Cleans up all sent offers via NIP-09.
     */
    fun cancelRide(reason: String = "rider cancelled") {
        val ride = _currentRide.value ?: return
        Log.d(TAG, "Cancelling ride ${ride.rideId}: $reason")
        batchJob?.cancel()
        _currentRide.value = ride.copy(state = RideState.CANCELLED)
        // Close acceptance subscriptions
        acceptanceSubscriptionIds.forEach { nostrService.closeSubscription(it) }
        acceptanceSubscriptionIds.clear()
        // Clean up all sent offers
        val allOffers = contactedDrivers.values.toList()
        if (allOffers.isNotEmpty()) {
            scope.launch {
                nostrService.deleteEvents(allOffers, reason)
            }
        }
        updateStage()
    }

    /**
     * Update the ride to a new state (e.g., EN_ROUTE, ARRIVED, IN_PROGRESS, COMPLETED).
     */
    fun updateRideState(newState: RideState) {
        _currentRide.value = _currentRide.value?.copy(state = newState)
        updateStage()
    }

    /**
     * Clear all ride session state, returning to IDLE.
     */
    fun clearRide() {
        _currentRide.value = null
        contactedDrivers.clear()
        acceptedDrivers.clear()
        acceptanceDataMap.clear()
        acceptanceSubscriptionIds.forEach { nostrService.closeSubscription(it) }
        acceptanceSubscriptionIds.clear()
        driverNames = emptyMap()
        hasSentOffers = false
        batchJob?.cancel()
        updateStage()
    }

    /**
     * Permanently tear down this coordinator. Cancels the coroutine scope
     * and cleans up all ride state. Called from RiderViewModel.onCleared().
     */
    fun destroy() {
        clearRide()
        scope.cancel()
    }

    @VisibleForTesting
    internal fun isScopeActive(): Boolean = scope.isActive

    @VisibleForTesting
    internal fun activeSubscriptionCount(): Int = acceptanceSubscriptionIds.size

    @VisibleForTesting
    internal fun addTestSubscriptionId(id: String) { acceptanceSubscriptionIds.add(id) }

    /**
     * Get the list of drivers who have accepted the current ride offer.
     */
    fun getAcceptedDrivers(): List<DriverInfo> = acceptedDrivers.toList()

    /**
     * Derive the UI stage from the current ride state and metadata.
     */
    private fun updateStage() {
        val ride = _currentRide.value
        _rideStage.value = if (ride == null) {
            RideStage.IDLE
        } else {
            RideStage.fromRideState(
                state = ride.state,
                hasAcceptances = acceptedDrivers.isNotEmpty(),
                hasSentOffers = hasSentOffers
            )
        }
    }
}
