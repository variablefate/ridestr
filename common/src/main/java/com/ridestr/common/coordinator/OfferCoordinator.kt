package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.RideOfferSpec
import com.ridestr.common.nostr.RouteMetrics
import com.ridestr.common.nostr.SubscriptionManager
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.nostr.events.RideshareExpiration
import com.ridestr.common.payment.PaymentCrypto
import com.ridestr.common.payment.WalletService
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.settings.RemoteConfigManager
import com.ridestr.common.settings.SettingsRepository
import com.ridestr.common.util.FareCalculator
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

// ==================== Public event hierarchy ====================

/**
 * Events emitted by [OfferCoordinator] describing offer lifecycle transitions.
 * Collect from [OfferCoordinator.events] to drive ViewModel state.
 */
sealed class OfferEvent {
    /** An offer was successfully published to Nostr. */
    data class Sent(val offer: SentOffer) : OfferEvent()

    /** Offer publication failed (Nostr error). */
    data class SendFailed(val message: String) : OfferEvent()

    /**
     * A driver accepted the offer.
     * @param isBatch true when the acceptance came from a secondary batch subscription
     *   (sendRoadflareToAll); false for direct, broadcast, and single-RoadFlare flows.
     */
    data class Accepted(val acceptance: RideAcceptanceData, val isBatch: Boolean) : OfferEvent()

    /** Direct offer acceptance timeout expired (15 s) — no driver responded. */
    object DirectOfferTimedOut : OfferEvent()

    /** Broadcast timeout expired (120 s) — no driver accepted. */
    object BroadcastTimedOut : OfferEvent()

    /** Selected driver went offline / deleted availability during WAITING_FOR_ACCEPTANCE. */
    object DriverUnavailable : OfferEvent()

    /** Batch progress update — how many drivers have been contacted so far. */
    data class BatchProgress(val contacted: Int, val total: Int) : OfferEvent()

    /**
     * Wallet balance insufficient to send the offer.
     *
     * @param shortfall Sats short of the required amount.
     * @param isRoadflare True when triggered from a RoadFlare single-offer path.
     * @param isBatch True when triggered from the batch (sendRoadflareToAll) path.
     * @param pendingDriverPubKey Driver pubkey stored for deferred retry (single RoadFlare only).
     * @param pendingDriverLocation Driver location stored for deferred retry (single RoadFlare only).
     */
    data class InsufficientFunds(
        val shortfall: Long,
        val isRoadflare: Boolean,
        val isBatch: Boolean,
        val pendingDriverPubKey: String? = null,
        val pendingDriverLocation: Location? = null
    ) : OfferEvent()
}

// ==================== Public data classes ====================

/**
 * Immutable snapshot of a successfully-sent offer.
 * Replaces the private [OfferParams] internal to RiderViewModel.
 */
data class SentOffer(
    val eventId: String,
    val driverPubKey: String,
    val driverAvailabilityEventId: String?,
    val pickup: Location,
    val destination: Location,
    val fareEstimate: Double,
    val fareEstimateWithFees: Double,
    val rideRoute: RouteResult?,
    val preimage: String?,
    val paymentHash: String?,
    val paymentMethod: String,
    val fiatPaymentMethods: List<String>,
    val isRoadflare: Boolean,
    val isBroadcast: Boolean,
    val riderMintUrl: String?,
    val roadflareTargetPubKey: String?,
    val roadflareTargetLocation: Location?,
    val fareFiatAmount: String?,
    val fareFiatCurrency: String?,
    val driverAvailabilityCreatedAt: Long = 0L
)

/**
 * Result of a fare calculation.
 * Carries sats (actual or heuristic fallback) and the authoritative USD quote
 * used for fiat/manual offers.
 */
data class FareCalc(val sats: Double, val usdAmount: String?)

// ==================== Coordinator ====================

/**
 * Encapsulates the complete offer-sending lifecycle for the rider side:
 * direct offers, RoadFlare offers, batch RoadFlare, broadcast, acceptance subscriptions,
 * availability monitoring, and all timeout/cleanup logic.
 *
 * All results are emitted via [events]; the ViewModel layer is responsible for applying
 * those events to its own [StateFlow] and triggering downstream actions (e.g.
 * [autoConfirmRide]).
 *
 * The coordinator uses its own internal [CoroutineScope] (SupervisorJob + Dispatchers.Main).
 * Call [destroy] when the owning ViewModel is cleared.
 *
 * TODO(#52): convert to @Singleton @Inject
 */
class OfferCoordinator(
    private val nostrService: NostrService,
    private val settingsRepository: SettingsRepository,
    private val routingService: ValhallaRoutingService,
    private val remoteConfigManager: RemoteConfigManager,
    private val bitcoinPriceService: BitcoinPriceService
) {

    companion object {
        private const val TAG = "OfferCoordinator"

        /** Fee buffer for cross-mint payments (Lightning routing + melt fees). */
        const val FEE_BUFFER_PERCENT = 0.02

        /** Time to wait for driver to accept direct offer before emitting [OfferEvent.DirectOfferTimedOut]. */
        const val ACCEPTANCE_TIMEOUT_MS = 15_000L

        /** Time to wait for any driver to accept a broadcast request before emitting [OfferEvent.BroadcastTimedOut]. */
        const val BROADCAST_TIMEOUT_MS = 120_000L

        /**
         * Grace period before reacting to Kind 5 availability deletion during WAITING_FOR_ACCEPTANCE.
         * Allows acceptance (Kind 3174) to arrive before surfacing a false "driver unavailable" event.
         */
        const val DELETION_GRACE_PERIOD_MS = 3000L

        /** Number of drivers to contact per RoadFlare batch. */
        const val ROADFLARE_BATCH_SIZE = 3

        /** Delay between RoadFlare batches in milliseconds. */
        const val ROADFLARE_BATCH_DELAY_MS = 15_000L
    }

    // ==================== Internal state ====================

    /** Own scope — NOT the ViewModel scope, so tests can control lifecycle independently. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * WalletService injected after wallet initialisation.
     * Null until set by the owning ViewModel.
     * TODO(#52): inject via constructor once wallet is DI-managed
     */
    var walletService: WalletService? = null

    private val _events = MutableSharedFlow<OfferEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /** Stream of offer lifecycle events. Collect in the owning ViewModel. */
    val events: SharedFlow<OfferEvent> = _events.asSharedFlow()

    private val subs = SubscriptionManager(nostrService::closeSubscription)

    /** Tracks which drivers have been contacted in the current batch (pubkey → offerEventId). */
    private val contactedDrivers = mutableMapOf<String, String>()

    /** CAS guard: only one acceptance wins per offer cycle. */
    private val hasAcceptedDriver = AtomicBoolean(false)

    /** Set to true while a direct-offer acceptance subscription is active. */
    private val isWaitingForDirectAcceptance = AtomicBoolean(false)

    private var selectedDriverLastAvailabilityTimestamp: Long = 0L

    private var roadflareBatchJob: Job? = null
    private var acceptanceTimeoutJob: Job? = null
    private var broadcastTimeoutJob: Job? = null
    private var pendingDeletionJob: Job? = null

    /** Stored for retryBatchWithAlternatePayment(). */
    private var pendingBatchDrivers: List<FollowedDriver>? = null
    private var pendingBatchLocations: Map<String, Location>? = null

    // ==================== Sub-keys ====================

    private object SubKeys {
        const val ACCEPTANCE = "acceptance"
        const val SELECTED_DRIVER_AVAILABILITY = "selected_driver_availability"
        const val SELECTED_DRIVER_AVAIL_DELETION = "selected_driver_avail_deletion"
        const val BATCH_ACCEPTANCE = "batch_acceptance"
    }

    // ==================== Internal data classes ====================

    /**
     * Computed parameters for a single offer send.
     * All public entry points construct this and delegate to [sendOfferToNostr].
     */
    private data class InternalOfferParams(
        val driverPubKey: String,
        val driverAvailabilityEventId: String?,
        val driverLocation: Location?,
        val pickup: Location,
        val destination: Location,
        val fareEstimate: Double,
        val rideRoute: RouteResult?,
        val preimage: String?,
        val paymentHash: String?,
        val paymentMethod: String,
        val isRoadflare: Boolean,
        val isBroadcast: Boolean,
        val roadflareTargetPubKey: String?,
        val roadflareTargetLocation: Location?,
        val fiatPaymentMethods: List<String> = emptyList(),
        val fareFiatAmount: String? = null,
        val fareFiatCurrency: String? = null
    )

    /**
     * Frozen ride inputs for batch consistency — precheck and sends use the same location/route values.
     */
    private data class FrozenRideInputs(
        val pickup: Location,
        val destination: Location,
        val rideRoute: RouteResult?
    )

    /**
     * Driver info with pre-calculated pickup route for sorting and sending in a batch.
     */
    private data class DriverWithRoute(
        val driver: FollowedDriver,
        val location: Location?,
        val pickupRoute: RouteResult?,
        val distanceKm: Double
    )

    // ==================== Public API ====================

    /**
     * Send a direct ride offer to a specific driver.
     *
     * Emits [OfferEvent.InsufficientFunds] if the wallet balance is too low for a Cashu offer.
     * Emits [OfferEvent.Sent] on success (caller must set UI stage, start availability monitoring, etc.).
     * Emits [OfferEvent.SendFailed] if Nostr publication fails.
     *
     * @param driver The driver's current availability data.
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param fareEstimate Fare in sats.
     * @param fareEstimateUsd Authoritative USD fare string (ADR-0008), or null for non-fiat rails.
     * @param fareEstimateWithFees Displayed fare (fare + fee buffer).
     * @param routeResult Pre-calculated ride route, or null.
     */
    fun sendRideOffer(
        driver: DriverAvailabilityData,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        fareEstimateUsd: String?,
        fareEstimateWithFees: Double,
        routeResult: RouteResult?
    ) {
        val paymentMethod = settingsRepository.getDefaultPaymentMethod()
        val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()
        val (offerFiatAmount, offerFiatCurrency) = if (isFiatPaymentMethod(paymentMethod) && fareEstimateUsd != null) {
            fareEstimateUsd to "USD"
        } else null to null

        scope.launch {
            if (paymentMethod == PaymentMethod.CASHU.value && !verifyWalletBalance(
                    fareWithBuffer,
                    isRoadflare = false,
                    isBatch = false
                )
            ) return@launch

            val preimage = PaymentCrypto.generatePreimage()
            val paymentHash = PaymentCrypto.computePaymentHash(preimage)

            val params = InternalOfferParams(
                driverPubKey = driver.driverPubKey,
                driverAvailabilityEventId = driver.eventId,
                driverLocation = driver.approxLocation,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                rideRoute = routeResult,
                preimage = preimage,
                paymentHash = paymentHash,
                paymentMethod = paymentMethod,
                isRoadflare = false,
                isBroadcast = false,
                roadflareTargetPubKey = null,
                roadflareTargetLocation = null,
                fareFiatAmount = offerFiatAmount,
                fareFiatCurrency = offerFiatCurrency
            )

            val pickupRoute = calculatePickupRoute(driver.approxLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent direct offer: $eventId with payment hash")
                val sentOffer = buildSentOffer(params, eventId, fareEstimateWithFees, driverAvailabilityCreatedAt = driver.createdAt)
                setupOfferSubscriptions(
                    eventId = eventId,
                    driverPubKey = driver.driverPubKey,
                    isBroadcast = false,
                    driverAvailabilityEventId = driver.eventId,
                    driverAvailabilityCreatedAt = driver.createdAt
                )
                _events.emit(OfferEvent.Sent(sentOffer))
            } else {
                _events.emit(OfferEvent.SendFailed("Failed to send ride offer"))
            }
        }
    }

    /**
     * Send a RoadFlare ride offer to a specific followed driver.
     *
     * If the driver's location is known, the fare is computed from driver→pickup + ride distance.
     * If null, the caller-supplied [fareCalc] is used directly.
     *
     * Emits [OfferEvent.InsufficientFunds] for Cashu offers when balance is insufficient.
     * Emits [OfferEvent.Sent] on success, [OfferEvent.SendFailed] on failure.
     *
     * @param driverPubKey The driver's Nostr public key.
     * @param driverLocation The driver's current location from Kind 30014, or null if offline.
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param rideRoute Pre-calculated ride route, or null.
     * @param fareCalc Fallback fare when driverLocation is null.
     */
    fun sendRoadflareOffer(
        driverPubKey: String,
        driverLocation: Location?,
        pickup: Location,
        destination: Location,
        rideRoute: RouteResult?,
        fareCalc: FareCalc
    ) {
        val resolvedFareCalc = if (driverLocation != null) {
            calculateRoadflareFare(pickup, driverLocation, rideRoute)
        } else {
            fareCalc
        }
        val fareEstimate = resolvedFareCalc.sats
        val paymentMethod = settingsRepository.getDefaultPaymentMethod()
        val fiatMethods = if (paymentMethod != PaymentMethod.CASHU.value) {
            settingsRepository.getRoadflarePaymentMethods()
        } else emptyList()
        val (offerFiatAmount, offerFiatCurrency) = if (isFiatPaymentMethod(paymentMethod) && resolvedFareCalc.usdAmount != null) {
            resolvedFareCalc.usdAmount to "USD"
        } else null to null

        if (paymentMethod == PaymentMethod.CASHU.value) {
            val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()
            val currentBalance = walletService?.getBalance() ?: 0L
            if (currentBalance < fareWithBuffer) {
                val shortfall = fareWithBuffer - currentBalance
                Log.w(TAG, "Insufficient funds for RoadFlare: need $fareWithBuffer, have $currentBalance")
                scope.launch {
                    _events.emit(
                        OfferEvent.InsufficientFunds(
                            shortfall = shortfall,
                            isRoadflare = true,
                            isBatch = false,
                            pendingDriverPubKey = driverPubKey,
                            pendingDriverLocation = driverLocation
                        )
                    )
                }
                return
            }
        }

        scope.launch {
            val preimage: String?
            val paymentHash: String?
            if (paymentMethod == PaymentMethod.CASHU.value) {
                preimage = PaymentCrypto.generatePreimage()
                paymentHash = PaymentCrypto.computePaymentHash(preimage)
            } else {
                preimage = null
                paymentHash = null
            }

            val params = InternalOfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = null,
                driverLocation = driverLocation,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                rideRoute = rideRoute,
                preimage = preimage,
                paymentHash = paymentHash,
                paymentMethod = paymentMethod,
                isRoadflare = true,
                isBroadcast = false,
                roadflareTargetPubKey = driverPubKey,
                roadflareTargetLocation = driverLocation,
                fiatPaymentMethods = fiatMethods,
                fareFiatAmount = offerFiatAmount,
                fareFiatCurrency = offerFiatCurrency
            )

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent RoadFlare offer to ${driverPubKey.take(16)}: $eventId")
                val fareWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
                val sentOffer = buildSentOffer(params, eventId, fareWithFees)
                setupOfferSubscriptions(eventId = eventId, driverPubKey = driverPubKey, isBroadcast = false)
                _events.emit(OfferEvent.Sent(sentOffer))
            } else {
                _events.emit(OfferEvent.SendFailed("Failed to send RoadFlare offer"))
            }
        }
    }

    /**
     * Send a RoadFlare offer with an explicit alternate (non-bitcoin) payment method.
     * Skips the bitcoin balance check since payment will be handled outside the app.
     *
     * Emits [OfferEvent.Sent] on success, [OfferEvent.SendFailed] on failure.
     *
     * @param driverPubKey The driver's Nostr public key.
     * @param driverLocation The driver's current location from Kind 30014, or null if offline.
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param rideRoute Pre-calculated ride route, or null.
     * @param paymentMethod The alternate payment method string (e.g. "zelle", "venmo").
     * @param fareCalc Fallback fare when driverLocation is null.
     */
    fun sendRoadflareOfferWithAlternatePayment(
        driverPubKey: String,
        driverLocation: Location?,
        pickup: Location,
        destination: Location,
        rideRoute: RouteResult?,
        paymentMethod: String,
        fareCalc: FareCalc
    ) {
        val resolvedFareCalc = if (driverLocation != null) {
            calculateRoadflareFare(pickup, driverLocation, rideRoute)
        } else {
            fareCalc
        }
        val fareEstimate = resolvedFareCalc.sats
        val (offerFiatAmount, offerFiatCurrency) = if (isFiatPaymentMethod(paymentMethod) && resolvedFareCalc.usdAmount != null) {
            resolvedFareCalc.usdAmount to "USD"
        } else null to null

        scope.launch {
            val params = InternalOfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = null,
                driverLocation = driverLocation,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                rideRoute = rideRoute,
                preimage = null,
                paymentHash = null,
                paymentMethod = paymentMethod,
                isRoadflare = true,
                isBroadcast = false,
                roadflareTargetPubKey = driverPubKey,
                roadflareTargetLocation = driverLocation,
                fiatPaymentMethods = settingsRepository.getRoadflarePaymentMethods(),
                fareFiatAmount = offerFiatAmount,
                fareFiatCurrency = offerFiatCurrency
            )

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent RoadFlare offer with $paymentMethod to ${driverPubKey.take(16)}: $eventId")
                val fareWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
                val sentOffer = buildSentOffer(params, eventId, fareWithFees)
                setupOfferSubscriptions(eventId = eventId, driverPubKey = driverPubKey, isBroadcast = false)
                _events.emit(OfferEvent.Sent(sentOffer))
            } else {
                _events.emit(OfferEvent.SendFailed("Failed to send RoadFlare offer"))
            }
        }
    }

    /**
     * Broadcast a public ride request visible to all drivers in the pickup area.
     *
     * Emits [OfferEvent.InsufficientFunds] if wallet balance is insufficient.
     * Emits [OfferEvent.Sent] on success (isBroadcast = true in the [SentOffer]).
     * Emits [OfferEvent.SendFailed] on Nostr failure.
     *
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param fareEstimate Fare in sats.
     * @param fareEstimateUsd Authoritative USD fare string (ADR-0008), or null for non-fiat rails.
     * @param routeResult Pre-calculated ride route (required for broadcast metric encoding).
     */
    fun broadcastRideRequest(
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        fareEstimateUsd: String?,
        routeResult: RouteResult
    ) {
        val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()

        scope.launch {
            if (!verifyWalletBalance(fareWithBuffer, isRoadflare = false, isBatch = false)) return@launch

            val preimage = PaymentCrypto.generatePreimage()
            val paymentHash = PaymentCrypto.computePaymentHash(preimage)
            val riderMintUrl = walletService?.getSavedMintUrl()
            val paymentMethod = settingsRepository.getDefaultPaymentMethod()
            val (offerFiatAmount, offerFiatCurrency) = if (isFiatPaymentMethod(paymentMethod) && fareEstimateUsd != null) {
                fareEstimateUsd to "USD"
            } else null to null

            val spec = RideOfferSpec.Broadcast(
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                routeDistance = RouteMetrics.fromSeconds(routeResult.distanceKm, routeResult.durationSeconds),
                mintUrl = riderMintUrl,
                paymentMethod = paymentMethod,
                fareFiatAmount = offerFiatAmount,
                fareFiatCurrency = offerFiatCurrency
            )
            val eventId = nostrService.sendOffer(spec)

            if (eventId != null) {
                Log.d(TAG, "Broadcast ride request: $eventId")
                val fareWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
                val sentOffer = SentOffer(
                    eventId = eventId,
                    driverPubKey = "",
                    driverAvailabilityEventId = null,
                    pickup = pickup,
                    destination = destination,
                    fareEstimate = fareEstimate,
                    fareEstimateWithFees = fareWithFees,
                    rideRoute = routeResult,
                    preimage = preimage,
                    paymentHash = paymentHash,
                    paymentMethod = paymentMethod,
                    fiatPaymentMethods = emptyList(),
                    isRoadflare = false,
                    isBroadcast = true,
                    riderMintUrl = riderMintUrl,
                    roadflareTargetPubKey = null,
                    roadflareTargetLocation = null,
                    fareFiatAmount = offerFiatAmount,
                    fareFiatCurrency = offerFiatCurrency
                )
                setupOfferSubscriptions(eventId = eventId, driverPubKey = "", isBroadcast = true)
                _events.emit(OfferEvent.Sent(sentOffer))
            } else {
                _events.emit(OfferEvent.SendFailed("Failed to broadcast ride request"))
            }
        }
    }

    /**
     * Send RoadFlare ride offers to followed drivers in batches.
     *
     * Sends to [ROADFLARE_BATCH_SIZE] closest drivers at a time, waits [ROADFLARE_BATCH_DELAY_MS]
     * for a response, then continues to the next batch until a driver accepts or all are contacted.
     *
     * Emits [OfferEvent.InsufficientFunds] (isBatch=true) for Cashu when balance is low.
     * Emits [OfferEvent.BatchProgress] as each batch is dispatched.
     * Emits [OfferEvent.Sent] for the first offer in the batch.
     * Emits [OfferEvent.Accepted] when a driver accepts.
     *
     * @param drivers Followed drivers to contact (filtered to those with a RoadFlare key).
     * @param driverLocations Map of driver pubkey → current location from Kind 30014.
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param rideRoute Pre-calculated ride route, or null.
     * @param paymentMethod Payment method to use for all offers in this batch.
     */
    fun sendRoadflareToAll(
        drivers: List<FollowedDriver>,
        driverLocations: Map<String, Location>,
        pickup: Location,
        destination: Location,
        rideRoute: RouteResult?,
        paymentMethod: String
    ) {
        val eligibleDrivers = drivers.filter { it.roadflareKey != null }
        if (eligibleDrivers.isEmpty()) {
            Log.w(TAG, "No eligible RoadFlare drivers to send to")
            scope.launch {
                _events.emit(OfferEvent.SendFailed("No favorite drivers available"))
            }
            return
        }

        Log.d(TAG, "Starting RoadFlare broadcast to ${eligibleDrivers.size} drivers — calculating routes...")

        // Freeze route inputs for retryBatchWithAlternatePayment()
        pendingBatchPickup = pickup
        pendingBatchDestination = destination
        pendingBatchRideRoute = rideRoute

        // Clear previous batch state
        contactedDrivers.clear()
        pendingBatchDrivers = null
        pendingBatchLocations = null
        roadflareBatchJob?.cancel()
        roadflareBatchJob = null
        subs.closeGroup(SubKeys.BATCH_ACCEPTANCE)

        roadflareBatchJob = scope.launch {
            // Pre-calculate routes for all online drivers
            val driversWithRoutes = eligibleDrivers.map { driver ->
                val location = driverLocations[driver.pubkey]
                if (location != null && routingService.isReady()) {
                    val pickupRoute = routingService.calculateRoute(
                        originLat = location.lat,
                        originLon = location.lon,
                        destLat = pickup.lat,
                        destLon = pickup.lon
                    )
                    if (pickupRoute != null) {
                        DriverWithRoute(driver, location, pickupRoute, pickupRoute.distanceKm)
                    } else {
                        val hav = haversineDistance(pickup.lat, pickup.lon, location.lat, location.lon) / 1000.0
                        DriverWithRoute(driver, location, null, hav)
                    }
                } else if (location != null) {
                    val hav = haversineDistance(pickup.lat, pickup.lon, location.lat, location.lon) / 1000.0
                    DriverWithRoute(driver, location, null, hav)
                } else {
                    DriverWithRoute(driver, null, null, Double.MAX_VALUE)
                }
            }

            val sortedDrivers = driversWithRoutes.sortedBy { it.distanceKm }

            Log.d(TAG, "Route calculation complete. Order:")
            sortedDrivers.forEachIndexed { index, dwr ->
                val distStr = if (dwr.distanceKm == Double.MAX_VALUE) "offline"
                else String.format("%.1f km (%.1f mi)", dwr.distanceKm, dwr.distanceKm * 0.621371)
                val routeType = if (dwr.pickupRoute != null) "route" else "haversine"
                Log.d(TAG, "  ${index + 1}. ${dwr.driver.pubkey.take(12)} - $distStr ($routeType)")
            }

            // Fare-cap filter — skip cap when no ride route (cap is meaningless without ride distance)
            var tooFarCount = 0
            var noLocationCount = 0
            val cappedDrivers = if (rideRoute != null) {
                val config = remoteConfigManager.config.value
                val rideMiles = rideRoute.distanceKm * FareCalculator.KM_TO_MILES
                val normalFareUsd = FareCalculator.calculateFareUsd(rideMiles, config.fareRateUsdPerMile, config.minimumFareUsd)

                sortedDrivers.filter { dwr ->
                    if (dwr.location == null) {
                        noLocationCount++; false
                    } else {
                        val pickupMiles = dwr.distanceKm * FareCalculator.KM_TO_MILES
                        val fareUsd = FareCalculator.calculateFareUsd(
                            pickupMiles + rideMiles,
                            config.roadflareFareRateUsdPerMile,
                            config.roadflareMinimumFareUsd
                        )
                        val tooFar = FareCalculator.isTooFar(fareUsd, normalFareUsd)
                        if (tooFar) {
                            tooFarCount++
                            Log.d(TAG, "  Excluding ${dwr.driver.pubkey.take(12)} — fare ${"%.2f".format(fareUsd)} > max ${"%.2f".format(normalFareUsd + FareCalculator.ROADFLARE_MAX_SURCHARGE_USD)}")
                        }
                        !tooFar
                    }
                }
            } else {
                Log.d(TAG, "No ride route — skipping fare cap filter")
                sortedDrivers.filter { dwr ->
                    if (dwr.location == null) {
                        noLocationCount++; false
                    } else true
                }
            }

            if (cappedDrivers.isEmpty()) {
                val msg = when {
                    rideRoute == null -> "No favorite drivers currently share location"
                    tooFarCount > 0 -> "All favorite drivers are too far for this ride"
                    else -> "No favorite drivers currently share location"
                }
                Log.w(TAG, "No eligible RoadFlare drivers: $msg (tooFar=$tooFarCount, noLocation=$noLocationCount)")
                _events.emit(OfferEvent.SendFailed(msg))
                return@launch
            }

            if (cappedDrivers.size < sortedDrivers.size) {
                val excluded = sortedDrivers.size - cappedDrivers.size
                Log.d(TAG, "Excluded $excluded drivers from batch (fare cap or no location)")
            }

            // Balance precheck for Cashu payment
            if (paymentMethod == PaymentMethod.CASHU.value) {
                var maxFareSats = 0.0
                for (dwr in cappedDrivers) {
                    val loc = dwr.location ?: continue
                    val fareSats = calculateRoadflareFareWithRoute(pickup, loc, rideRoute, dwr.pickupRoute).sats
                    if (fareSats > maxFareSats) maxFareSats = fareSats
                }
                val fareWithBuffer = (maxFareSats * (1 + FEE_BUFFER_PERCENT)).toLong()
                val currentBalance = walletService?.getBalance() ?: 0L
                if (currentBalance < fareWithBuffer) {
                    val shortfall = fareWithBuffer - currentBalance
                    Log.w(TAG, "Insufficient funds for batch RoadFlare: need $fareWithBuffer, have $currentBalance")
                    pendingBatchDrivers = drivers
                    pendingBatchLocations = driverLocations
                    _events.emit(
                        OfferEvent.InsufficientFunds(
                            shortfall = shortfall,
                            isRoadflare = true,
                            isBatch = true
                        )
                    )
                    return@launch
                }
            }

            sendRoadflareBatches(
                sortedDrivers = cappedDrivers,
                pickup = pickup,
                destination = destination,
                rideRoute = rideRoute,
                paymentMethod = paymentMethod
            )
        }
    }

    /**
     * Retry the batch RoadFlare send with an alternate (non-bitcoin) payment method.
     * Uses the stored [pendingBatchDrivers] / [pendingBatchLocations] from the previous
     * insufficient-funds failure.
     *
     * No-op if there is no pending batch or the ride is not currently IDLE
     * (caller must gate on stage before calling).
     *
     * @param paymentMethod The alternate payment method to retry with.
     */
    fun retryBatchWithAlternatePayment(paymentMethod: String) {
        val drivers = pendingBatchDrivers
        val locations = pendingBatchLocations
        val pickup = pendingBatchPickup
        val destination = pendingBatchDestination
        if (drivers != null && locations != null && pickup != null && destination != null) {
            val rideRoute = pendingBatchRideRoute
            pendingBatchDrivers = null
            pendingBatchLocations = null
            // Note: pendingBatchPickup/Destination/RideRoute are re-set at the top of
            // sendRoadflareToAll(), so we don't need to clear them here.
            sendRoadflareToAll(
                drivers = drivers,
                driverLocations = locations,
                pickup = pickup,
                destination = destination,
                rideRoute = rideRoute,
                paymentMethod = paymentMethod
            )
        } else {
            Log.w(TAG, "retryBatchWithAlternatePayment: missing batch payload")
        }
    }

    /**
     * Cancel a pending offer event via NIP-09 deletion and close all offer subscriptions.
     *
     * Safe to call in any state — no-ops gracefully if nothing is pending.
     *
     * @param offerEventId Event ID to delete, or null to skip the deletion step.
     */
    fun cancelOffer(offerEventId: String?) {
        isWaitingForDirectAcceptance.set(false)
        closeAllSubscriptionsAndJobs()
        scope.launch {
            offerEventId?.let {
                nostrService.deleteEvent(it, "offer cancelled")
            }
        }
    }

    /**
     * Boost a broadcast request: delete the old offer, cancel its timeout, and re-broadcast
     * with the new fare.
     *
     * Emits [OfferEvent.Sent] (isBroadcast=true) on success.
     * Emits [OfferEvent.SendFailed] on Nostr failure.
     *
     * @param currentOfferEventId Event ID of the active broadcast to delete, or null.
     * @param newFare New fare in sats after boost.
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param fareEstimateUsd null for boosted broadcasts (no authoritative USD per ADR-0008).
     * @param routeResult Pre-calculated ride route.
     */
    fun boostBroadcast(
        currentOfferEventId: String?,
        newFare: Double,
        pickup: Location,
        destination: Location,
        fareEstimateUsd: String?,
        routeResult: RouteResult
    ) {
        scope.launch {
            cancelBroadcastTimeout()
            currentOfferEventId?.let {
                Log.d(TAG, "Deleting old offer before boost: $it")
                nostrService.deleteEvent(it, "fare boosted")
            }
            subs.close(SubKeys.ACCEPTANCE)
            subs.closeGroup(SubKeys.BATCH_ACCEPTANCE)
        }
        broadcastRideRequest(pickup, destination, newFare, fareEstimateUsd, routeResult)
    }

    /**
     * Boost a direct or RoadFlare offer: delete the old offer, cancel its timeout, and resend
     * to the same driver with the new fare.
     *
     * Emits [OfferEvent.Sent] on success, [OfferEvent.SendFailed] on failure.
     *
     * @param currentOfferEventId Event ID of the active offer to delete, or null.
     * @param driverPubKey The driver's Nostr public key.
     * @param driverAvailabilityEventId Driver availability event ID (null for RoadFlare).
     * @param driverLocation Driver location (null for RoadFlare or post-restore).
     * @param pickup Rider's pickup location.
     * @param destination Rider's destination.
     * @param newFare Boosted fare in sats.
     * @param rideRoute Pre-calculated ride route, or null.
     * @param paymentMethod Payment method for the re-sent offer.
     * @param isRoadflare True if this is a RoadFlare offer.
     * @param directOfferBoostSats Cumulative boost sats so far (for logging).
     */
    fun boostDirectOffer(
        currentOfferEventId: String?,
        driverPubKey: String,
        driverAvailabilityEventId: String?,
        driverLocation: Location?,
        pickup: Location,
        destination: Location,
        newFare: Double,
        rideRoute: RouteResult?,
        paymentMethod: String,
        isRoadflare: Boolean,
        directOfferBoostSats: Double
    ) {
        scope.launch {
            cancelAcceptanceTimeout()
            currentOfferEventId?.let {
                Log.d(TAG, "Deleting old direct offer before boost: $it")
                nostrService.deleteEvent(it, "fare boosted")
            }
            roadflareBatchJob?.cancel()
            roadflareBatchJob = null
            subs.close(SubKeys.ACCEPTANCE)
            subs.closeGroup(SubKeys.BATCH_ACCEPTANCE)

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)

            val params = InternalOfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = driverAvailabilityEventId,
                driverLocation = driverLocation,
                pickup = pickup,
                destination = destination,
                fareEstimate = newFare,
                rideRoute = rideRoute,
                preimage = null,
                paymentHash = null, // Boost reuses existing HTLC
                paymentMethod = paymentMethod,
                isRoadflare = isRoadflare,
                isBroadcast = false,
                roadflareTargetPubKey = null,
                roadflareTargetLocation = null,
                fiatPaymentMethods = if (isRoadflare && paymentMethod != PaymentMethod.CASHU.value) {
                    settingsRepository.getRoadflarePaymentMethods()
                } else emptyList()
                // Boosted offer: no fareFiatAmount/Currency per ADR-0008
            )

            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent boosted ${if (isRoadflare) "RoadFlare" else "direct"} offer: $eventId")
                val fareWithFees = newFare * (1 + FEE_BUFFER_PERCENT)
                val sentOffer = buildSentOffer(params, eventId, fareWithFees)
                isWaitingForDirectAcceptance.set(true)
                subscribeToAcceptance(eventId, driverPubKey)
                startAcceptanceTimeout()
                _events.emit(OfferEvent.Sent(sentOffer))
            } else {
                _events.emit(OfferEvent.SendFailed("Failed to resend boosted offer"))
            }
        }
    }

    /**
     * Restart the broadcast timeout without modifying any other state.
     * Called when the rider chooses "Continue Waiting" on a timed-out broadcast.
     */
    fun continueWaiting() {
        startBroadcastTimeout()
    }

    /**
     * Restart the acceptance timeout without modifying any other state.
     * Called when the rider chooses "Continue Waiting" on a timed-out direct offer.
     */
    fun continueWaitingDirect() {
        cancelAcceptanceTimeout()
        startAcceptanceTimeout()
    }

    /**
     * Notify the coordinator that the ViewModel has successfully processed an [OfferEvent.Accepted]
     * event and the ride is proceeding.
     *
     * Cancels availability monitoring, batch subscriptions, and the batch job.
     * Clears [contactedDrivers].
     */
    fun onAcceptanceHandled() {
        isWaitingForDirectAcceptance.set(false)
        pendingDeletionJob?.cancel()
        pendingDeletionJob = null
        subs.close(SubKeys.SELECTED_DRIVER_AVAILABILITY)
        subs.close(SubKeys.SELECTED_DRIVER_AVAIL_DELETION)
        subs.closeGroup(SubKeys.BATCH_ACCEPTANCE)
        roadflareBatchJob?.cancel()
        roadflareBatchJob = null
        contactedDrivers.clear()
    }

    /**
     * Delete all batch offers except the one from the accepting driver via NIP-09 (Kind 5).
     *
     * INVARIANT: must be called inside a CAS winner block only — i.e. only when
     * [OfferEvent.Accepted] has been acted on and the stage has transitioned atomically.
     *
     * @param acceptedDriverPubKey The driver pubkey whose offer should be kept.
     */
    fun cancelNonAcceptedBatchOffers(acceptedDriverPubKey: String) {
        val nonAcceptedOfferIds = contactedDrivers
            .filter { (pubkey, _) -> pubkey != acceptedDriverPubKey }
            .values.toList()
        if (nonAcceptedOfferIds.isNotEmpty()) {
            scope.launch {
                var deletedCount = 0
                nonAcceptedOfferIds.chunked(50).forEach { chunk ->
                    val result = nostrService.deleteEvents(chunk, "batch: rider chose another driver")
                    if (result != null) {
                        deletedCount += chunk.size
                    } else {
                        Log.w(TAG, "Failed to delete ${chunk.size} batch offers — will be cleaned up by backgroundCleanupRideshareEvents")
                    }
                }
                Log.d(TAG, "Batch offer deletion complete: $deletedCount/${nonAcceptedOfferIds.size} deleted")
            }
            Log.d(TAG, "Cancelling ${nonAcceptedOfferIds.size} non-accepted batch offers")
        }
        contactedDrivers.clear()
    }

    /**
     * Close all Nostr subscriptions and cancel all pending jobs managed by this coordinator.
     * Safe to call at any point; operations are idempotent.
     */
    fun closeAllSubscriptionsAndJobs() {
        cancelAcceptanceTimeout()
        cancelBroadcastTimeout()
        pendingDeletionJob?.cancel()
        pendingDeletionJob = null
        roadflareBatchJob?.cancel()
        roadflareBatchJob = null
        isWaitingForDirectAcceptance.set(false)
        selectedDriverLastAvailabilityTimestamp = 0L
        subs.closeAll()
        contactedDrivers.clear()
    }

    /**
     * Return a snapshot of the contacted-drivers map (pubkey → offerEventId).
     * Used by the ViewModel to persist batch state across process death.
     */
    fun getContactedDrivers(): Map<String, String> = contactedDrivers.toMap()

    /**
     * Calculate the RoadFlare fare for a driver at a given location.
     *
     * Uses driver→pickup haversine distance as pickup leg and ride route (if available)
     * for the trip leg.
     *
     * @param pickup Rider's pickup location.
     * @param driverLocation Driver's current location.
     * @param rideRoute Pre-calculated ride route, or null.
     */
    fun calculateRoadflareFare(
        pickup: Location,
        driverLocation: Location,
        rideRoute: RouteResult?
    ): FareCalc {
        return calculateRoadflareFareWithRoute(pickup, driverLocation, rideRoute, null)
    }

    /**
     * Calculate the RoadFlare fare using an actual route for the pickup leg when available.
     * Falls back to haversine if [pickupRoute] is null.
     *
     * @param pickup Rider's pickup location.
     * @param driverLocation Driver's current location.
     * @param rideRoute Pre-calculated ride route, or null.
     * @param preCalculatedRoute Pre-calculated driver→pickup route, or null.
     */
    fun calculateRoadflareFareWithRoute(
        pickup: Location,
        driverLocation: Location,
        rideRoute: RouteResult?,
        preCalculatedRoute: RouteResult?
    ): FareCalc {
        val config = remoteConfigManager.config.value
        val roadflareRatePerMile = config.roadflareFareRateUsdPerMile
        val metersPerMile = 1609.34

        val driverToPickupMiles = if (preCalculatedRoute != null) {
            preCalculatedRoute.distanceKm * 0.621371
        } else {
            val meters = haversineDistance(
                driverLocation.lat, driverLocation.lon,
                pickup.lat, pickup.lon
            )
            meters / metersPerMile
        }

        val rideMiles = rideRoute?.let { it.distanceKm * 0.621371 } ?: 0.0
        val minimumFareUsd = config.roadflareMinimumFareUsd
        val calculatedFare = (driverToPickupMiles + rideMiles) * roadflareRatePerMile
        val fareUsd = maxOf(calculatedFare, minimumFareUsd)

        val sats = bitcoinPriceService.usdToSats(fareUsd)
        val minimumFallbackSats = 5000.0
        // Build authoritative fare quote: USD is always authoritative; sats falls back if price unavailable
        val satsFinal = sats?.toDouble() ?: minimumFallbackSats
        val usdAmount = String.format(Locale.US, "%.2f", fareUsd)
        return FareCalc(sats = satsFinal, usdAmount = usdAmount)
    }

    /**
     * Cancel scope and clear all state. Call from ViewModel.onCleared().
     */
    fun destroy() {
        closeAllSubscriptionsAndJobs()
        scope.coroutineContext[Job]?.cancel()
    }

    // ==================== Internal helpers ====================

    /**
     * Per ADR-0008, fiat fare fields are encoded only for fiat payment rails.
     * Crypto rails (cashu/lightning) skip the fields — sats is canonical there.
     */
    private fun isFiatPaymentMethod(paymentMethod: String): Boolean =
        paymentMethod !in setOf(PaymentMethod.CASHU.value, PaymentMethod.LIGHTNING.value)

    /**
     * Publish an offer event to Nostr.
     * Builds the appropriate [RideOfferSpec] variant (RoadFlare or Direct) from [params].
     *
     * @return The published event ID, or null on failure.
     */
    private suspend fun sendOfferToNostr(
        params: InternalOfferParams,
        pickupRoute: RouteResult?
    ): String? {
        val riderMintUrl = walletService?.getSavedMintUrl()
        val pickupMetrics = pickupRoute?.let { RouteMetrics.fromSeconds(it.distanceKm, it.durationSeconds) }
        val rideMetrics = params.rideRoute?.let { RouteMetrics.fromSeconds(it.distanceKm, it.durationSeconds) }
        val spec = if (params.isRoadflare) {
            RideOfferSpec.RoadFlare(
                driverPubKey = params.driverPubKey,
                pickup = params.pickup,
                destination = params.destination,
                fareEstimate = params.fareEstimate,
                pickupRoute = pickupMetrics,
                rideRoute = rideMetrics,
                mintUrl = riderMintUrl,
                paymentMethod = params.paymentMethod,
                fiatPaymentMethods = params.fiatPaymentMethods,
                fareFiatAmount = params.fareFiatAmount,
                fareFiatCurrency = params.fareFiatCurrency
            )
        } else {
            RideOfferSpec.Direct(
                driverPubKey = params.driverPubKey,
                driverAvailabilityEventId = params.driverAvailabilityEventId,
                pickup = params.pickup,
                destination = params.destination,
                fareEstimate = params.fareEstimate,
                pickupRoute = pickupMetrics,
                rideRoute = rideMetrics,
                mintUrl = riderMintUrl,
                paymentMethod = params.paymentMethod,
                fiatPaymentMethods = params.fiatPaymentMethods,
                fareFiatAmount = params.fareFiatAmount,
                fareFiatCurrency = params.fareFiatCurrency
            )
        }
        return nostrService.sendOffer(spec)
    }

    /**
     * Build a [SentOffer] from [InternalOfferParams] and the resulting event ID.
     */
    private fun buildSentOffer(
        params: InternalOfferParams,
        eventId: String,
        fareEstimateWithFees: Double,
        driverAvailabilityCreatedAt: Long = 0L
    ): SentOffer {
        val riderMintUrl = walletService?.getSavedMintUrl()
        return SentOffer(
            eventId = eventId,
            driverPubKey = params.driverPubKey,
            driverAvailabilityEventId = params.driverAvailabilityEventId,
            pickup = params.pickup,
            destination = params.destination,
            fareEstimate = params.fareEstimate,
            fareEstimateWithFees = fareEstimateWithFees,
            rideRoute = params.rideRoute,
            preimage = params.preimage,
            paymentHash = params.paymentHash,
            paymentMethod = params.paymentMethod,
            fiatPaymentMethods = params.fiatPaymentMethods,
            isRoadflare = params.isRoadflare,
            isBroadcast = params.isBroadcast,
            riderMintUrl = riderMintUrl,
            roadflareTargetPubKey = params.roadflareTargetPubKey,
            roadflareTargetLocation = params.roadflareTargetLocation,
            fareFiatAmount = params.fareFiatAmount,
            fareFiatCurrency = params.fareFiatCurrency,
            driverAvailabilityCreatedAt = driverAvailabilityCreatedAt
        )
    }

    /**
     * Calculate the driver→pickup route for accurate metrics on the driver's card.
     * Returns null if driver location is unknown or routing service isn't ready.
     */
    private suspend fun calculatePickupRoute(
        driverLocation: Location?,
        pickup: Location
    ): RouteResult? {
        if (driverLocation == null || !routingService.isReady()) return null
        return routingService.calculateRoute(
            originLat = driverLocation.lat,
            originLon = driverLocation.lon,
            destLat = pickup.lat,
            destLon = pickup.lon
        )
    }

    /**
     * Verify wallet has sufficient balance for the fare (with fee buffer).
     * Emits [OfferEvent.InsufficientFunds] if balance is too low.
     *
     * @return true if wallet is ready, false if insufficient funds (event emitted).
     */
    private suspend fun verifyWalletBalance(
        fareWithBuffer: Long,
        isRoadflare: Boolean,
        isBatch: Boolean
    ): Boolean {
        val walletReady = walletService?.ensureWalletReady(fareWithBuffer) ?: false
        if (!walletReady) {
            val currentBalance = walletService?.getBalance() ?: 0L
            val shortfall = (fareWithBuffer - currentBalance).coerceAtLeast(0)
            Log.w(TAG, "Wallet not ready: need $fareWithBuffer sats, verified balance insufficient (shortfall=$shortfall)")
            _events.emit(
                OfferEvent.InsufficientFunds(
                    shortfall = shortfall,
                    isRoadflare = isRoadflare,
                    isBatch = isBatch
                )
            )
            return false
        }
        return true
    }

    /**
     * Haversine distance calculation in meters.
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    // ==================== Subscription setup ====================

    /**
     * Set up post-send subscriptions: acceptance monitoring, driver availability monitoring,
     * and the appropriate timeout.
     */
    private fun setupOfferSubscriptions(
        eventId: String,
        driverPubKey: String,
        isBroadcast: Boolean,
        driverAvailabilityEventId: String? = null,
        driverAvailabilityCreatedAt: Long = 0L,
        fromRestore: Boolean = false
    ) {
        if (isBroadcast) {
            hasAcceptedDriver.set(false)
            subscribeToAcceptancesForBroadcast(eventId)
            if (!fromRestore) {
                startBroadcastTimeout()
            }
        } else {
            isWaitingForDirectAcceptance.set(true)
            subscribeToAcceptance(eventId, driverPubKey)
            subscribeToSelectedDriverAvailability(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = driverAvailabilityEventId,
                initialAvailabilityTimestamp = driverAvailabilityCreatedAt
            )
            Log.d(
                TAG,
                "[AVAIL-DIAG] Subscriptions armed: acceptance + availability for driver ${driverPubKey.take(8)}, " +
                        "offerEventId=$eventId, availEventId=$driverAvailabilityEventId, seed=$driverAvailabilityCreatedAt"
            )
            if (!fromRestore) {
                startAcceptanceTimeout()
            }
        }
    }

    /**
     * Subscribe to Kind 3174 acceptance for a direct offer from a specific driver.
     * CAS guard prevents duplicate processing.
     */
    private fun subscribeToAcceptance(offerEventId: String, expectedDriverPubKey: String) {
        subs.set(SubKeys.ACCEPTANCE, nostrService.subscribeToAcceptance(offerEventId, expectedDriverPubKey) { acceptance ->
            Log.d(
                TAG,
                "[AVAIL-DIAG] Kind 3174 acceptance received: eventId=${acceptance.eventId}, " +
                        "driver=${acceptance.driverPubKey.take(8)}, expected=${expectedDriverPubKey.take(8)}"
            )
            cancelAcceptanceTimeout()

            scope.launch {
                _events.emit(OfferEvent.Accepted(acceptance, isBatch = false))
            }
        })
    }

    /**
     * Subscribe to Kind 3174 acceptances for a broadcast offer.
     * First-acceptance-wins via AtomicBoolean CAS.
     */
    private fun subscribeToAcceptancesForBroadcast(offerEventId: String) {
        subs.set(SubKeys.ACCEPTANCE, nostrService.subscribeToAcceptancesForOffer(offerEventId) { acceptance ->
            // First-acceptance-wins: only one thread proceeds
            if (!hasAcceptedDriver.compareAndSet(false, true)) {
                Log.d(TAG, "Ignoring duplicate acceptance from ${acceptance.driverPubKey.take(8)} — already accepted")
                return@subscribeToAcceptancesForOffer
            }

            Log.d(TAG, "First driver accepted broadcast! ${acceptance.driverPubKey.take(8)}")
            cancelBroadcastTimeout()

            scope.launch {
                _events.emit(OfferEvent.Accepted(acceptance, isBatch = false))
            }
        })
    }

    /**
     * Monitor the selected driver's availability while waiting for acceptance.
     * If driver goes offline (deletes Kind 30173 or takes another ride), emits
     * [OfferEvent.DriverUnavailable] after [DELETION_GRACE_PERIOD_MS].
     */
    private fun subscribeToSelectedDriverAvailability(
        driverPubKey: String,
        driverAvailabilityEventId: String? = null,
        initialAvailabilityTimestamp: Long = 0L
    ) {
        // Close existing subscription and reset timestamp guard
        subs.close(SubKeys.SELECTED_DRIVER_AVAILABILITY)
        subs.close(SubKeys.SELECTED_DRIVER_AVAIL_DELETION)
        pendingDeletionJob?.cancel()
        pendingDeletionJob = null
        selectedDriverLastAvailabilityTimestamp = 0L

        val seedValue = AvailabilityMonitorPolicy.seedTimestamp(initialAvailabilityTimestamp)
        selectedDriverLastAvailabilityTimestamp = seedValue

        Log.d(TAG, "Monitoring availability for selected driver ${driverPubKey.take(8)}")

        subs.set(
            SubKeys.SELECTED_DRIVER_AVAILABILITY,
            nostrService.subscribeToDriverAvailability(driverPubKey) { availability ->
                Log.d(
                    TAG,
                    "[AVAIL-DIAG] Kind 30173 received: isAvailable=${availability.isAvailable}, " +
                            "createdAt=${availability.createdAt}, seed=$selectedDriverLastAvailabilityTimestamp"
                )
                if (availability.createdAt < selectedDriverLastAvailabilityTimestamp) {
                    Log.d(TAG, "[AVAIL-DIAG] Kind 30173 STALE — rejected")
                    return@subscribeToDriverAvailability
                }
                selectedDriverLastAvailabilityTimestamp = availability.createdAt

                val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
                    isWaitingForAcceptance = isWaitingForDirectAcceptance.get(),
                    isAvailable = availability.isAvailable,
                    eventCreatedAt = availability.createdAt,
                    lastSeenTimestamp = selectedDriverLastAvailabilityTimestamp
                )
                Log.d(TAG, "[AVAIL-DIAG] Availability policy decision: $action")
                when (action) {
                    AvailabilityMonitorPolicy.Action.IGNORE -> return@subscribeToDriverAvailability
                    AvailabilityMonitorPolicy.Action.DEFER_CHECK -> {
                        Log.d(TAG, "Deferring availability-offline reaction — waiting ${DELETION_GRACE_PERIOD_MS}ms for possible acceptance")
                        pendingDeletionJob?.cancel()
                        pendingDeletionJob = scope.launch {
                            delay(DELETION_GRACE_PERIOD_MS)
                            if (isWaitingForDirectAcceptance.get()) {
                                Log.w(TAG, "[AVAIL-DIAG] Grace period expired — driver ${driverPubKey.take(8)} no longer available (status: ${availability.status})")
                                _events.emit(OfferEvent.DriverUnavailable)
                            } else {
                                Log.d(TAG, "Ignoring deferred availability-offline — no longer waiting for acceptance")
                            }
                        }
                    }
                }
            }
        )

        // Subscribe to Kind 5 deletions of this driver's availability.
        // Catches: driver accepts another ride and deletes availability without going offline first.
        val deletionSince = if (driverAvailabilityEventId != null) {
            (System.currentTimeMillis() / 1000) - RideshareExpiration.DRIVER_AVAILABILITY_LOOKBACK_SECONDS
        } else {
            System.currentTimeMillis() / 1000
        }

        subs.set(
            SubKeys.SELECTED_DRIVER_AVAIL_DELETION,
            nostrService.subscribeToAvailabilityDeletions(
                driverPubKey = driverPubKey,
                availabilityEventId = driverAvailabilityEventId,
                since = deletionSince
            ) { deletionTimestamp ->
                Log.d(
                    TAG,
                    "[AVAIL-DIAG] Kind 5 received: deletionTs=$deletionTimestamp, seed=$selectedDriverLastAvailabilityTimestamp"
                )
                if (deletionTimestamp < selectedDriverLastAvailabilityTimestamp) {
                    Log.d(TAG, "[AVAIL-DIAG] Kind 5 STALE — rejected")
                    return@subscribeToAvailabilityDeletions
                }

                val action = AvailabilityMonitorPolicy.onDeletionEvent(
                    isWaitingForAcceptance = isWaitingForDirectAcceptance.get(),
                    deletionTimestamp = deletionTimestamp,
                    lastSeenTimestamp = selectedDriverLastAvailabilityTimestamp
                )
                when (action) {
                    AvailabilityMonitorPolicy.Action.IGNORE -> {
                        Log.d(TAG, "Ignoring availability deletion")
                        return@subscribeToAvailabilityDeletions
                    }
                    AvailabilityMonitorPolicy.Action.DEFER_CHECK -> {
                        Log.d(TAG, "Deferring deletion reaction — waiting ${DELETION_GRACE_PERIOD_MS}ms for possible acceptance")
                        pendingDeletionJob?.cancel()
                        pendingDeletionJob = scope.launch {
                            delay(DELETION_GRACE_PERIOD_MS)
                            if (isWaitingForDirectAcceptance.get()) {
                                Log.w(TAG, "[AVAIL-DIAG] Grace period expired — driver ${driverPubKey.take(8)} deleted availability, no acceptance arrived")
                                _events.emit(OfferEvent.DriverUnavailable)
                            } else {
                                Log.d(TAG, "Ignoring deferred deletion — no longer waiting for acceptance")
                            }
                        }
                    }
                }
            }
        )
    }

    // ==================== Timeout management ====================

    private fun startAcceptanceTimeout() {
        cancelAcceptanceTimeout()
        Log.d(TAG, "Starting acceptance timeout (${ACCEPTANCE_TIMEOUT_MS / 1000}s)")
        acceptanceTimeoutJob = scope.launch {
            delay(ACCEPTANCE_TIMEOUT_MS)
            handleAcceptanceTimeout()
        }
    }

    private fun cancelAcceptanceTimeout() {
        acceptanceTimeoutJob?.cancel()
        acceptanceTimeoutJob = null
    }

    private fun handleAcceptanceTimeout() {
        if (!isWaitingForDirectAcceptance.get()) {
            Log.d(TAG, "Acceptance timeout ignored — no longer waiting for direct acceptance")
            return
        }
        Log.d(TAG, "Direct offer timeout — no response from driver")
        scope.launch {
            _events.emit(OfferEvent.DirectOfferTimedOut)
        }
    }

    private fun startBroadcastTimeout() {
        cancelBroadcastTimeout()
        Log.d(TAG, "Starting broadcast timeout (${BROADCAST_TIMEOUT_MS / 1000}s)")
        broadcastTimeoutJob = scope.launch {
            delay(BROADCAST_TIMEOUT_MS)
            handleBroadcastTimeout()
        }
    }

    private fun cancelBroadcastTimeout() {
        broadcastTimeoutJob?.cancel()
        broadcastTimeoutJob = null
    }

    private fun handleBroadcastTimeout() {
        Log.d(TAG, "Broadcast timeout — no driver accepted")
        scope.launch {
            _events.emit(OfferEvent.BroadcastTimedOut)
        }
    }

    // ==================== Batch sending ====================

    /**
     * Send RoadFlare offers in sorted batches, with inter-batch wait for acceptance.
     */
    private suspend fun sendRoadflareBatches(
        sortedDrivers: List<DriverWithRoute>,
        pickup: Location,
        destination: Location,
        rideRoute: RouteResult?,
        paymentMethod: String
    ) {
        val batches = sortedDrivers.chunked(ROADFLARE_BATCH_SIZE)
        var batchIndex = 0
        var firstOffer = true  // Track whether we've sent the first offer (sets up UI state via Sent event)

        for (batch in batches) {
            // Check for acceptance (stage has advanced)
            if (!isFirstBatchOrStillWaiting(batchIndex)) {
                Log.d(TAG, "RoadFlare broadcast: Not in initial batch and stage changed, stopping")
                return
            }

            batchIndex++
            Log.d(TAG, "RoadFlare batch $batchIndex/${batches.size}: Sending to ${batch.size} drivers")

            _events.emit(
                OfferEvent.BatchProgress(
                    contacted = contactedDrivers.size + batch.size,
                    total = sortedDrivers.size
                )
            )

            // Send to all drivers in this batch
            for (dwr in batch) {
                coroutineContext.ensureActive()
                if (dwr.driver.pubkey in contactedDrivers) continue

                val distanceInfo = if (dwr.location != null) {
                    val distMiles = dwr.distanceKm * 0.621371
                    String.format("%.1f mi away", distMiles)
                } else "offline"
                Log.d(TAG, "  -> Sending to ${dwr.driver.pubkey.take(12)} ($distanceInfo)")

                val sentFirstOfferThisBatch = sendRoadflareOfferSilent(
                    driverPubKey = dwr.driver.pubkey,
                    driverLocation = dwr.location,
                    preCalculatedRoute = dwr.pickupRoute,
                    paymentMethod = paymentMethod,
                    frozenInputs = FrozenRideInputs(pickup, destination, rideRoute),
                    isFirstOffer = firstOffer
                )
                if (sentFirstOfferThisBatch) firstOffer = false
            }

            // Wait between batches (skip for last batch)
            if (batchIndex < batches.size) {
                Log.d(TAG, "RoadFlare batch $batchIndex: Waiting ${ROADFLARE_BATCH_DELAY_MS / 1000}s for response...")
                repeat((ROADFLARE_BATCH_DELAY_MS / 1000).toInt()) {
                    delay(1000)
                    // Stop if acceptance arrived or rider cancelled (isWaitingForDirectAcceptance flipped to false)
                    if (!isWaitingForDirectAcceptance.get()) {
                        Log.d(TAG, "RoadFlare broadcast: No longer waiting for acceptance during inter-batch delay, stopping")
                        return
                    }
                }
            }
        }

        Log.d(TAG, "RoadFlare broadcast complete: contacted ${contactedDrivers.size} drivers")
    }

    /**
     * True when this is the first batch (batchIndex == 0 before increment) OR
     * the batch loop is ongoing. The ViewModel tracks stage; the coordinator
     * uses [isWaitingForDirectAcceptance] as a proxy for "still active and waiting".
     * After the first offer, [isWaitingForDirectAcceptance] is set to true.
     */
    private fun isFirstBatchOrStillWaiting(batchIndex: Int): Boolean {
        // batchIndex == 0 means we haven't sent the first batch yet — always proceed
        if (batchIndex == 0) return true
        // After first offer, isWaitingForDirectAcceptance is true until acceptance or cancellation
        return isWaitingForDirectAcceptance.get()
    }

    /**
     * Send a single RoadFlare offer as part of a batch without updating main UI state.
     *
     * If this is the first offer in the batch ([isFirstOffer]=true), emits [OfferEvent.Sent]
     * to trigger ViewModel stage transition and sets up the acceptance timeout.
     * Subsequent offers add a batch-keyed acceptance subscription only.
     *
     * @return true if this was the first offer (i.e. [OfferEvent.Sent] was emitted).
     */
    private suspend fun sendRoadflareOfferSilent(
        driverPubKey: String,
        driverLocation: Location?,
        preCalculatedRoute: RouteResult? = null,
        paymentMethod: String = PaymentMethod.CASHU.value,
        frozenInputs: FrozenRideInputs,
        isFirstOffer: Boolean
    ): Boolean {
        val pickup = frozenInputs.pickup
        val destination = frozenInputs.destination
        val rideRoute = frozenInputs.rideRoute

        val fareCalc = if (driverLocation != null) {
            calculateRoadflareFareWithRoute(pickup, driverLocation, rideRoute, preCalculatedRoute)
        } else {
            return false  // No location — skip (offline drivers filtered before this point)
        }
        val fareEstimate = fareCalc.sats

        val (preimage, paymentHash) = if (paymentMethod == PaymentMethod.CASHU.value) {
            val pi = PaymentCrypto.generatePreimage()
            val ph = PaymentCrypto.computePaymentHash(pi)
            pi to ph
        } else null to null

        val pickupRoute = preCalculatedRoute ?: calculatePickupRoute(driverLocation, pickup)

        val fiatMethods = if (paymentMethod != PaymentMethod.CASHU.value) {
            settingsRepository.getRoadflarePaymentMethods()
        } else emptyList()
        val (offerFiatAmount, offerFiatCurrency) = if (isFiatPaymentMethod(paymentMethod) && fareCalc.usdAmount != null) {
            fareCalc.usdAmount to "USD"
        } else null to null

        val params = InternalOfferParams(
            driverPubKey = driverPubKey,
            driverAvailabilityEventId = null,
            driverLocation = driverLocation,
            pickup = pickup,
            destination = destination,
            fareEstimate = fareEstimate,
            rideRoute = rideRoute,
            preimage = preimage,
            paymentHash = paymentHash,
            paymentMethod = paymentMethod,
            isRoadflare = true,
            isBroadcast = false,
            roadflareTargetPubKey = driverPubKey,
            roadflareTargetLocation = driverLocation,
            fiatPaymentMethods = fiatMethods,
            fareFiatAmount = offerFiatAmount,
            fareFiatCurrency = offerFiatCurrency
        )

        val eventId = sendOfferToNostr(params, pickupRoute)
        if (eventId == null) {
            Log.w(TAG, "Failed to send RoadFlare batch offer to ${driverPubKey.take(12)}")
            return false
        }

        Log.d(TAG, "Sent RoadFlare batch offer to ${driverPubKey.take(12)}: ${eventId.take(12)}")

        // Post-publish cancellation check: if batch job was cancelled during in-flight send,
        // delete the orphan event and skip tracking/subscription setup.
        if (!coroutineContext.isActive) {
            Log.w(TAG, "Batch cancelled during in-flight send — deleting orphan ${eventId.take(12)}")
            scope.launch { nostrService.deleteEvents(listOf(eventId), "batch cancelled during send") }
            return false
        }

        contactedDrivers[driverPubKey] = eventId

        return if (isFirstOffer) {
            // First offer: set up subscriptions and emit Sent to trigger ViewModel stage transition
            isWaitingForDirectAcceptance.set(true)
            subscribeToAcceptance(eventId, driverPubKey)
            startAcceptanceTimeout()

            val fareWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
            val sentOffer = buildSentOffer(params, eventId, fareWithFees)
            _events.emit(OfferEvent.Sent(sentOffer))
            true
        } else {
            // Additional offer: add a batch-keyed acceptance subscription
            val batchSubId = nostrService.subscribeToAcceptance(eventId, driverPubKey) { acceptance ->
                handleBatchAcceptance(acceptance)
            }
            subs.setInGroup(SubKeys.BATCH_ACCEPTANCE, eventId, batchSubId)
            false
        }
    }

    /**
     * Handle an acceptance arriving on a secondary batch subscription.
     * Emits [OfferEvent.Accepted] with isBatch=true after CAS stage guard.
     */
    private fun handleBatchAcceptance(acceptance: RideAcceptanceData) {
        Log.d(TAG, "RoadFlare batch: Driver accepted! ${acceptance.driverPubKey.take(12)}")

        roadflareBatchJob?.cancel()
        roadflareBatchJob = null
        cancelAcceptanceTimeout()
        subs.close(SubKeys.ACCEPTANCE)
        subs.closeGroup(SubKeys.BATCH_ACCEPTANCE)

        scope.launch {
            _events.emit(OfferEvent.Accepted(acceptance, isBatch = true))
        }
    }

    // ==================== Pending batch retry fields ====================

    /** Frozen pickup for retryBatchWithAlternatePayment(). Set at the start of sendRoadflareToAll(). */
    private var pendingBatchPickup: Location? = null

    /** Frozen destination for retryBatchWithAlternatePayment(). Set at the start of sendRoadflareToAll(). */
    private var pendingBatchDestination: Location? = null

    /** Frozen ride route for retryBatchWithAlternatePayment(). Set at the start of sendRoadflareToAll(). */
    private var pendingBatchRideRoute: RouteResult? = null
}
