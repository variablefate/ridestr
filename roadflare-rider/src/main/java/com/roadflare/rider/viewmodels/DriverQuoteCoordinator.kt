package com.roadflare.rider.viewmodels

import android.util.Log
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.CachedDriverLocation
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.events.AdminConfig
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.ridestr.common.roadflare.FareState
import com.ridestr.common.roadflare.RoadflareDriverQuote
import com.ridestr.common.roadflare.RoadflareFarePolicy
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.util.FareCalculator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "DriverQuoteCoordinator"

/**
 * Progressive per-driver fare refinement for roadflare-rider.
 *
 * Keeps routing/refinement in the ViewModel layer. The composable only reads
 * [driverQuotes] and [routingComplete] StateFlows — no routing logic in the UI layer.
 *
 * Lifecycle:
 * - Started by: [RiderViewModel.recalculateFare] when both pickup + dest are set
 * - Auto-restarted by: next [start] call (auto-cancels previous session)
 * - Cancelled by: clearPickup(), clearDest(), ViewModel.onCleared()
 * - NOT cancelled by: selection screen back button (quotes stay warm for instant reopen)
 */
class DriverQuoteCoordinator(
    private val valhallaRoutingService: ValhallaRoutingService,
    private val tileManager: TileManager,
    private val followedDriversRepository: FollowedDriversRepository,
    private val scope: CoroutineScope
) {
    /** Per-driver quotes. CALCULATING entries have placeholder fare values — UI checks fareState. */
    private val _driverQuotes = MutableStateFlow<Map<String, RoadflareDriverQuote>>(emptyMap())
    val driverQuotes: StateFlow<Map<String, RoadflareDriverQuote>> = _driverQuotes.asStateFlow()

    /** True when all online+fresh drivers have EXACT or FALLBACK state (not CALCULATING). */
    private val _routingComplete = MutableStateFlow(false)
    val routingComplete: StateFlow<Boolean> = _routingComplete.asStateFlow()

    // Generation token — incremented on start()/cancel(). Stale route results are dropped.
    private val generationCounter = AtomicLong(0)

    // Cached exact pickup distances (survive across location observer recomputes)
    private val exactPickupDistances = ConcurrentHashMap<String, Double>()

    private var refinementJob: Job? = null
    private var locationObserverJob: Job? = null
    private var freshnessTickerJob: Job? = null
    private var currentPickup: Location? = null
    private var currentRideMiles: Double = 0.0
    private var currentConfig: AdminConfig? = null
    private var priceService: BitcoinPriceService? = null

    /**
     * Start quoting for all drivers. Called from recalculateFare() (prewarming).
     *
     * 1. Set all drivers with locations to CALCULATING (no fare shown).
     * 2. Init Valhalla tiles if needed.
     * 3. Compute exact routes per driver on Dispatchers.IO.
     * 4. Start location observer for live updates.
     * 5. Start freshness ticker (30s).
     */
    fun start(
        pickupLocation: Location,
        rideMiles: Double,
        config: AdminConfig,
        bitcoinPriceService: BitcoinPriceService?
    ) {
        cancel()  // Stop any previous session
        val gen = generationCounter.incrementAndGet()

        currentPickup = pickupLocation
        currentRideMiles = rideMiles
        currentConfig = config
        priceService = bitcoinPriceService
        exactPickupDistances.clear()

        // Phase 1: Set all drivers to CALCULATING
        setAllCalculating()

        // Phase 2: Background exact routing
        refinementJob = scope.launch {
            computeExactRoutesForAll(gen, pickupLocation)
        }

        // Phase 3: Observe live location updates
        startLocationObserver(gen)

        // Phase 4: Freshness ticker
        startFreshnessTicker()
    }

    private fun setAllCalculating() {
        val locations = followedDriversRepository.driverLocations.value
        val drivers = followedDriversRepository.drivers.value

        val quotes = drivers.mapNotNull { driver ->
            locations[driver.pubkey] ?: return@mapNotNull null
            driver.pubkey to RoadflareDriverQuote(
                fareUsd = 0.0,
                fareSats = null,
                pickupMiles = 0.0,
                rideMiles = currentRideMiles,
                totalMiles = 0.0,
                normalRideFareUsd = 0.0,
                isTooFar = false,
                fareState = FareState.CALCULATING
            )
        }.toMap()
        _driverQuotes.value = quotes
        _routingComplete.value = false
    }

    private suspend fun computeExactRoutesForAll(gen: Long, pickup: Location) {
        // Ensure Valhalla is ready
        if (!valhallaRoutingService.isReady()) {
            val tileSource = withContext(Dispatchers.IO) {
                tileManager.getTileForLocation(pickup.lat, pickup.lon)
            }
            if (tileSource != null) {
                withContext(Dispatchers.IO) {
                    valhallaRoutingService.initializeWithTileSource(tileSource)
                }
            } else {
                // No tile coverage — mark all as FALLBACK with real fares
                markAllFallback(gen, pickup)
                _routingComplete.value = true
                return
            }
        }

        val locations = followedDriversRepository.driverLocations.value
        val onlineDrivers = followedDriversRepository.drivers.value.filter { d ->
            locations[d.pubkey]?.let { it.status == RoadflareLocationEvent.Status.ONLINE && isFresh(it) } == true
        }

        for (driver in onlineDrivers) {
            if (!currentCoroutineContext().isActive) return
            if (gen != generationCounter.get()) return
            val cached = locations[driver.pubkey] ?: continue
            computeExactForOneDriver(gen, driver.pubkey, cached, pickup)
        }

        // Mark any remaining CALCULATING drivers as FALLBACK with real haversine fares
        if (gen == generationCounter.get()) {
            val config = currentConfig ?: return
            val locs = followedDriversRepository.driverLocations.value
            _driverQuotes.update { current ->
                val updated = current.toMutableMap()
                for ((pubkey, quote) in current) {
                    if (quote.fareState == FareState.CALCULATING) {
                        val cached = locs[pubkey]
                        if (cached != null) {
                            updated[pubkey] = RoadflareFarePolicy.quoteDriver(
                                pickup.lat, pickup.lon, cached.lat, cached.lon,
                                currentRideMiles, config,
                                pickupDistanceMiles = null,
                                fareState = FareState.FALLBACK,
                                bitcoinPriceService = priceService
                            )
                        } else {
                            updated.remove(pubkey)
                        }
                    }
                }
                updated
            }
            _routingComplete.value = true
        }
    }

    private suspend fun computeExactForOneDriver(
        gen: Long, pubkey: String, cached: CachedDriverLocation, pickup: Location
    ) {
        val route = withContext(Dispatchers.IO) {
            try {
                valhallaRoutingService.calculateRoute(
                    cached.lat, cached.lon, pickup.lat, pickup.lon
                )
            } catch (e: Exception) {
                Log.w(TAG, "Route calculation failed for ${pubkey.take(8)}: ${e.message}")
                null
            }
        }
        // Generation check BEFORE writing result
        if (gen != generationCounter.get()) return
        val config = currentConfig ?: return

        val exactMiles = route?.distanceKm?.let { it * FareCalculator.KM_TO_MILES }
        if (exactMiles != null) {
            exactPickupDistances[pubkey] = exactMiles
            val exactQuote = RoadflareFarePolicy.quoteDriver(
                pickup.lat, pickup.lon, cached.lat, cached.lon,
                currentRideMiles, config,
                pickupDistanceMiles = exactMiles,
                fareState = FareState.EXACT,
                bitcoinPriceService = priceService
            )
            _driverQuotes.update { it + (pubkey to exactQuote) }
        } else {
            // Route failed — fallback to haversine with FALLBACK state
            val fallbackQuote = RoadflareFarePolicy.quoteDriver(
                pickup.lat, pickup.lon, cached.lat, cached.lon,
                currentRideMiles, config,
                pickupDistanceMiles = null,
                fareState = FareState.FALLBACK,
                bitcoinPriceService = priceService
            )
            _driverQuotes.update { it + (pubkey to fallbackQuote) }
        }
    }

    private fun markAllFallback(gen: Long, pickup: Location) {
        if (gen != generationCounter.get()) return
        val config = currentConfig ?: return
        val locations = followedDriversRepository.driverLocations.value
        _driverQuotes.update { current ->
            current.mapValues { (pubkey, _) ->
                val cached = locations[pubkey] ?: return@mapValues current[pubkey]!!
                RoadflareFarePolicy.quoteDriver(
                    pickup.lat, pickup.lon, cached.lat, cached.lon,
                    currentRideMiles, config,
                    pickupDistanceMiles = null,
                    fareState = FareState.FALLBACK,
                    bitcoinPriceService = priceService
                )
            }
        }
    }

    /** Observe driverLocations StateFlow for live updates. */
    private fun startLocationObserver(gen: Long) {
        locationObserverJob?.cancel()
        locationObserverJob = scope.launch {
            var previousLocations = followedDriversRepository.driverLocations.value

            followedDriversRepository.driverLocations.collect { newLocations ->
                if (gen != generationCounter.get()) return@collect
                val pickup = currentPickup ?: return@collect
                val config = currentConfig ?: return@collect

                for ((pubkey, newLoc) in newLocations) {
                    val oldLoc = previousLocations[pubkey]
                    if (oldLoc == null || oldLoc.timestamp != newLoc.timestamp) {
                        // Driver moved — invalidate exact distance, set CALCULATING
                        exactPickupDistances.remove(pubkey)
                        val calcQuote = RoadflareDriverQuote(
                            fareUsd = 0.0, fareSats = null, pickupMiles = 0.0,
                            rideMiles = currentRideMiles, totalMiles = 0.0,
                            normalRideFareUsd = 0.0, isTooFar = false,
                            fareState = FareState.CALCULATING
                        )
                        _driverQuotes.update { it + (pubkey to calcQuote) }
                        _routingComplete.value = false

                        // Compute exact route
                        if (valhallaRoutingService.isReady()) {
                            val capturedGen = gen
                            scope.launch {
                                computeExactForOneDriver(capturedGen, pubkey, newLoc, pickup)
                                checkRoutingComplete()
                            }
                        } else {
                            // No Valhalla — compute haversine fallback
                            val fallback = RoadflareFarePolicy.quoteDriver(
                                pickup.lat, pickup.lon, newLoc.lat, newLoc.lon,
                                currentRideMiles, config,
                                pickupDistanceMiles = null,
                                fareState = FareState.FALLBACK,
                                bitcoinPriceService = priceService
                            )
                            _driverQuotes.update { it + (pubkey to fallback) }
                            checkRoutingComplete()
                        }
                    }
                }
                previousLocations = newLocations
            }
        }
    }

    /** Periodic freshness check — marks stale drivers as OFFLINE-visible. */
    private fun startFreshnessTicker() {
        freshnessTickerJob?.cancel()
        freshnessTickerJob = scope.launch {
            while (isActive) {
                delay(30_000)
                val locations = followedDriversRepository.driverLocations.value
                _driverQuotes.update { current ->
                    current.mapValues { (pubkey, quote) ->
                        val loc = locations[pubkey]
                        if (loc != null && !isFresh(loc) && quote.fareState != FareState.CALCULATING) {
                            // Driver aged past 5 min — keep visible but mark stale
                            quote.copy(fareState = FareState.FALLBACK)
                        } else quote
                    }
                }
                checkRoutingComplete()
            }
        }
    }

    private fun checkRoutingComplete() {
        val allResolved = _driverQuotes.value.values.none { it.fareState == FareState.CALCULATING }
        _routingComplete.value = allResolved
    }

    private fun isFresh(loc: CachedDriverLocation): Boolean =
        (System.currentTimeMillis() / 1000 - loc.timestamp) < 5 * 60

    /**
     * Cancel all quoting. Called from clearPickup(), clearDest(), ViewModel.onCleared().
     */
    fun cancel() {
        generationCounter.incrementAndGet()
        refinementJob?.cancel()
        locationObserverJob?.cancel()
        freshnessTickerJob?.cancel()
        refinementJob = null
        locationObserverJob = null
        freshnessTickerJob = null
    }
}
