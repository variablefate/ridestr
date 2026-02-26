package com.ridestr.common.nostr

import android.util.Log
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.*
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Domain service for all ride protocol Nostr operations.
 *
 * Handles the core ride flow: offers, acceptance, confirmation, state management,
 * chat, cancellation, and driver availability broadcasting.
 *
 * Event kinds handled:
 * - Kind 30173: Driver availability broadcast
 * - Kind 3173: Ride offer (rider → driver)
 * - Kind 3174: Ride acceptance (driver → rider)
 * - Kind 3175: Ride confirmation (rider → driver, with paymentHash + escrowToken)
 * - Kind 30180: Driver ride state
 * - Kind 30181: Rider ride state
 * - Kind 3178: Encrypted chat
 * - Kind 3179: Ride cancellation
 *
 * @param relayManager The RelayManager instance for relay connections
 * @param keyManager The KeyManager instance for accessing the user's signer
 */
class RideshareDomainService(
    private val relayManager: RelayManager,
    private val keyManager: KeyManager
) {

    companion object {
        private const val TAG = "RideshareDomainSvc"
    }

    // ==================== Driver Availability (Kind 30173) ====================

    /**
     * Broadcast driver availability.
     *
     * @param location Driver location (null for ROADFLARE_ONLY mode - invisible to geographic search)
     * @param status Driver status (STATUS_AVAILABLE or STATUS_OFFLINE)
     * @param vehicle Optional vehicle info
     * @param mintUrl Driver's Cashu mint URL
     * @param paymentMethods Supported payment methods
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastAvailability(
        location: Location? = null,
        status: String = DriverAvailabilityEvent.STATUS_AVAILABLE,
        vehicle: Vehicle? = null,
        mintUrl: String? = null,
        paymentMethods: List<String> = listOf("cashu")
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast availability: Not logged in")
            return null
        }

        return try {
            val event = DriverAvailabilityEvent.create(
                signer = signer,
                location = location,
                status = status,
                vehicle = vehicle,
                mintUrl = mintUrl,
                paymentMethods = paymentMethods
            )
            relayManager.publish(event)
            Log.d(TAG, "Broadcast availability: status=$status, location=${location != null}, vehicle=${vehicle?.shortName() ?: "none"} (${event.id})")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast availability", e)
            null
        }
    }

    /**
     * Subscribe to available drivers near the specified location.
     * Uses geohash filtering for efficient relay queries.
     *
     * Coverage:
     * - Default (expandSearch=false): ~24mi x 12mi (single cell), auto-expands near edges
     * - Expanded (expandSearch=true): ~72mi x 36mi (9 cells), guaranteed 20+ mile radius
     *
     * @param location The rider's current location for geohash filtering (optional)
     * @param expandSearch If true, always include neighboring cells for wider coverage
     * @param onEose Called when EOSE received from a relay (relayUrl). Fires once per relay.
     * @param onDriver Called when a driver availability event is received
     * @return Subscription ID for closing later
     */
    fun subscribeToDrivers(
        location: Location? = null,
        expandSearch: Boolean = false,
        onEose: ((String) -> Unit)? = null,
        onDriver: (DriverAvailabilityData) -> Unit
    ): String {
        val tags = mutableMapOf<String, List<String>>(
            "t" to listOf(RideshareTags.RIDESHARE_TAG)
        )

        // If location provided, add geohash filters
        location?.let {
            val geohashes = Geohash.getSearchAreaGeohashes(it.lat, it.lon, expandSearch)
            tags["g"] = geohashes  // Note: RelayManager adds # prefix automatically
            Log.d(TAG, "=== RIDER SUBSCRIBING TO DRIVERS ===")
            Log.d(TAG, "Rider location: ${it.lat}, ${it.lon}")
            Log.d(TAG, "Expanded search: $expandSearch")
            Log.d(TAG, "Geohash filter: $geohashes")
        } ?: run {
            Log.d(TAG, "=== RIDER SUBSCRIBING TO DRIVERS (no location filter) ===")
        }

        // Only get events from last 15 minutes to avoid stale drivers
        val fifteenMinutesAgo = (System.currentTimeMillis() / 1000) - (15 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_AVAILABILITY),
            tags = tags,
            since = fifteenMinutesAgo,
            onEose = onEose
        ) { event, _ ->
            DriverAvailabilityEvent.parse(event)?.let { data ->
                onDriver(data)
            }
        }
    }

    /**
     * Subscribe to a specific driver's availability updates.
     * Used to monitor if the selected driver goes offline while waiting for acceptance.
     * @param driverPubKey The driver's public key to monitor
     * @param onAvailability Called when the driver's availability status changes
     * @return Subscription ID for closing later
     */
    fun subscribeToDriverAvailability(
        driverPubKey: String,
        onAvailability: (DriverAvailabilityData) -> Unit
    ): String {
        Log.d(TAG, "Subscribing to availability for driver ${driverPubKey.take(8)}")

        // Align with STALE_DRIVER_TIMEOUT_MS - drivers broadcast every ~5 min
        // Using shorter window might miss valid availability events
        val tenMinutesAgo = (System.currentTimeMillis() / 1000) - RideshareExpiration.DRIVER_AVAILABILITY_LOOKBACK_SECONDS

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_AVAILABILITY),
            authors = listOf(driverPubKey),
            tags = mapOf("t" to listOf(RideshareTags.RIDESHARE_TAG)),
            since = tenMinutesAgo  // Filter out very old events from relay history
        ) { event, _ ->
            DriverAvailabilityEvent.parse(event)?.let { data ->
                Log.d(TAG, "Driver ${driverPubKey.take(8)} availability: ${data.status}")
                onAvailability(data)
            }
        }
    }

    /**
     * Subscribe to NIP-09 deletion of a driver's availability events (Kind 30173).
     * Detects when a driver deletes availability without broadcasting offline status
     * first (e.g., when accepting another ride).
     *
     * Two filter strategies (both include #k=30173 to exclude supersede deletes):
     * - If availabilityEventId is provided (direct offers): #e + #k=30173 + author
     * - If null (RoadFlare offers): #k=30173 + author
     *
     * @param onDeletion Called with the deletion event's created_at timestamp,
     *   so callers can compare against latest availability timestamp and ignore stale deletions.
     */
    fun subscribeToAvailabilityDeletions(
        driverPubKey: String,
        availabilityEventId: String?,
        since: Long,
        onDeletion: (deletionTimestamp: Long) -> Unit
    ): String {
        val filterDesc = if (availabilityEventId != null) "e-tag=${availabilityEventId.take(8)}" else "k-tag=30173"
        Log.d(TAG, "Subscribing to availability deletions for driver ${driverPubKey.take(8)} ($filterDesc)")

        // Both paths include #k=30173 to exclude supersede deletes (which omit the k-tag).
        // Direct offers also add #e for precise event ID matching.
        val kTag = RideshareEventKinds.DRIVER_AVAILABILITY.toString()
        val tags = if (availabilityEventId != null) {
            mapOf("e" to listOf(availabilityEventId), "k" to listOf(kTag))
        } else {
            mapOf("k" to listOf(kTag))
        }
        // Always include author filter — prevents malicious actors from faking deletions
        val authors = listOf(driverPubKey)

        return relayManager.subscribe(
            kinds = listOf(DeletionEvent.KIND),        // Kind 5
            authors = authors,
            tags = tags,
            since = since
        ) { event, _ ->
            // Verify the deletion actually targets Kind 30173.
            // Relay-side #k filter should already ensure this, but not all relays support it.
            // This is the first #k filter usage in the codebase — belt-and-suspenders check.
            val targetsAvailability = event.tags.any { it.size >= 2 && it[0] == "k" && it[1] == kTag }
            if (!targetsAvailability) return@subscribe

            Log.w(TAG, "Driver ${driverPubKey.take(8)} deleted availability (Kind 5: ${event.id.take(8)})")
            onDeletion(event.createdAt)
        }
    }

    // ==================== Ride Offers (Kind 3173) ====================

    /**
     * Send a ride offer to a driver.
     *
     * This unified method handles both regular offers (with driver availability event)
     * and RoadFlare offers (direct to driver pubkey without availability event).
     *
     * @param driverPubKey The driver's Nostr public key
     * @param driverAvailabilityEventId The driver's availability event ID (null for RoadFlare)
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Estimated fare in sats
     * @param pickupRouteKm Pre-calculated driver→pickup distance in km (optional)
     * @param pickupRouteMin Pre-calculated driver→pickup duration in minutes (optional)
     * @param rideRouteKm Pre-calculated pickup→destination distance in km (optional)
     * @param rideRouteMin Pre-calculated pickup→destination duration in minutes (optional)
     * @param mintUrl Rider's Cashu mint URL (optional)
     * @param paymentMethod Payment method (default "cashu")
     * @param isRoadflare True for RoadFlare requests from favorite drivers
     *
     * NOTE: paymentHash is NOT included in offers - it's sent in confirmation (Kind 3175)
     * after driver accepts. This ensures HTLC is locked with the correct driver wallet key.
     *
     * @return The event ID if successful, null on failure
     */
    suspend fun sendRideOffer(
        driverPubKey: String,
        driverAvailabilityEventId: String? = null,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        pickupRouteKm: Double? = null,
        pickupRouteMin: Double? = null,
        rideRouteKm: Double? = null,
        rideRouteMin: Double? = null,
        mintUrl: String? = null,
        paymentMethod: String? = "cashu",
        isRoadflare: Boolean = false,
        fiatPaymentMethods: List<String> = emptyList()
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send ride offer: Not logged in")
            return null
        }

        return try {
            val event = RideOfferEvent.create(
                signer = signer,
                driverAvailabilityEventId = driverAvailabilityEventId,
                driverPubKey = driverPubKey,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                pickupRouteKm = pickupRouteKm,
                pickupRouteMin = pickupRouteMin,
                rideRouteKm = rideRouteKm,
                rideRouteMin = rideRouteMin,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod ?: "cashu",
                isRoadflare = isRoadflare,
                fiatPaymentMethods = fiatPaymentMethods
            )
            relayManager.publish(event)
            val offerType = if (isRoadflare) "RoadFlare" else "ride"
            Log.d(TAG, "Sent $offerType offer to ${driverPubKey.take(16)}: ${event.id}, method: ${paymentMethod ?: "cashu"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ride offer", e)
            null
        }
    }

    /**
     * Broadcast a public ride request (visible to all drivers in the pickup area).
     * This is the new primary flow where riders broadcast and drivers choose to accept.
     *
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Fare in sats
     * @param routeDistanceKm Route distance in kilometers
     * @param routeDurationMin Route duration in minutes
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastRideRequest(
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        routeDistanceKm: Double,
        routeDurationMin: Double,
        mintUrl: String? = null,
        paymentMethod: String = "cashu"
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast ride request: Not logged in")
            return null
        }

        return try {
            val event = RideOfferEvent.createBroadcast(
                signer = signer,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                routeDistanceKm = routeDistanceKm,
                routeDurationMin = routeDurationMin,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Broadcast ride request: ${event.id} (fare=$fareEstimate sats, method=$paymentMethod)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast ride request", e)
            null
        }
    }

    /**
     * Subscribe to broadcast ride requests in an area (for drivers).
     * Filters by geohash to only see local requests.
     *
     * @param location Driver's current location for geohash filtering
     * @param expandSearch If true, include neighboring cells for wider coverage
     * @param onRequest Called when a ride request is received
     * @return Subscription ID for closing later
     */
    fun subscribeToBroadcastRideRequests(
        location: Location,
        expandSearch: Boolean = false,
        onRequest: (BroadcastRideOfferData) -> Unit
    ): String {
        // Get geohashes for driver's area
        val geohashes = Geohash.getSearchAreaGeohashes(location.lat, location.lon, expandSearch)
        Log.d(TAG, "=== DRIVER SUBSCRIBING TO RIDE REQUESTS ===")
        Log.d(TAG, "Driver location: ${location.lat}, ${location.lon}")
        Log.d(TAG, "Expanded search: $expandSearch")
        Log.d(TAG, "Geohash filter: $geohashes")

        // Only get requests from last 5 minutes to avoid stale ones
        val fiveMinutesAgo = (System.currentTimeMillis() / 1000) - (5 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf(
                "g" to geohashes,
                "t" to listOf(RideOfferEvent.RIDE_REQUEST_TAG)  // Only broadcast requests
            ),
            since = fiveMinutesAgo
        ) { event, _ ->
            // Parse as broadcast offer (not direct)
            RideOfferEvent.parseBroadcast(event)?.let { data ->
                Log.d(TAG, "Received ride request ${event.id.take(8)} from ${event.pubKey.take(8)}, fare=${data.fareEstimate}")
                onRequest(data)
            }
        }
    }

    /**
     * Subscribe to ride offers for the current user (as driver).
     * Direct offers are now NIP-44 encrypted for privacy.
     * Only returns offers from the last 10 minutes to avoid stale requests.
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param onOffer Called when a ride offer is received (after decryption)
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOffers(
        scope: CoroutineScope,
        onOffer: (RideOfferData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null
        val signer = keyManager.getSigner() ?: return null

        // Only get offers from last 10 minutes to avoid old requests
        val tenMinutesAgo = (System.currentTimeMillis() / 1000) - (10 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf("p" to listOf(myPubKey)),
            since = tenMinutesAgo
        ) { event, _ ->
            // Direct offers are now encrypted - parse and decrypt
            RideOfferEvent.parseEncrypted(event)?.let { encryptedData ->
                scope.launch {
                    RideOfferEvent.decrypt(signer, encryptedData)?.let { data ->
                        Log.d(TAG, "Decrypted direct offer from ${data.riderPubKey.take(8)}")
                        onOffer(data)
                    }
                }
            }
        }
    }

    // ==================== Ride Acceptance (Kind 3174) ====================

    /**
     * Accept a ride offer.
     * PIN is no longer included - the rider generates it and shares verbally.
     * @param offer The offer to accept
     * @param walletPubKey Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
     * @return The event ID if successful, null on failure
     */
    suspend fun acceptRide(
        offer: RideOfferData,
        walletPubKey: String? = null,
        mintUrl: String? = null,
        paymentMethod: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot accept ride: Not logged in")
            return null
        }

        return try {
            val event = RideAcceptanceEvent.create(
                signer = signer,
                offerEventId = offer.eventId,
                riderPubKey = offer.riderPubKey,
                walletPubKey = walletPubKey,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Accepted ride: ${event.id}, mint: ${mintUrl ?: "none"}, method: ${paymentMethod ?: "none"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept ride", e)
            null
        }
    }

    /**
     * Accept a broadcast ride request.
     * @param request The broadcast ride request to accept
     * @param walletPubKey Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
     * @return The event ID if successful, null on failure
     */
    suspend fun acceptBroadcastRide(
        request: BroadcastRideOfferData,
        walletPubKey: String? = null,
        mintUrl: String? = null,
        paymentMethod: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot accept broadcast ride: Not logged in")
            return null
        }

        return try {
            val event = RideAcceptanceEvent.create(
                signer = signer,
                offerEventId = request.eventId,
                riderPubKey = request.riderPubKey,
                walletPubKey = walletPubKey,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Accepted broadcast ride: ${event.id}, mint: ${mintUrl ?: "none"}, method: ${paymentMethod ?: "none"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept broadcast ride", e)
            null
        }
    }

    /**
     * Subscribe to deletion events (NIP-09 Kind 5) for ride request event IDs.
     * Used to detect when a rider cancels their broadcast request.
     *
     * @param eventIds List of ride request event IDs to watch for deletion
     * @param onDeletion Called with the event ID that was deleted
     * @return Subscription ID for closing later
     */
    fun subscribeToRideRequestDeletions(
        eventIds: List<String>,
        onDeletion: (String) -> Unit
    ): String? {
        if (eventIds.isEmpty()) return null

        Log.d(TAG, "Subscribing to deletion events for ${eventIds.size} ride requests")

        return relayManager.subscribe(
            kinds = listOf(DeletionEvent.KIND),
            tags = mapOf("e" to eventIds)
        ) { event, _ ->
            // Extract which event IDs are being deleted
            for (tag in event.tags) {
                if (tag.getOrNull(0) == "e") {
                    val deletedEventId = tag.getOrNull(1)
                    if (deletedEventId != null && deletedEventId in eventIds) {
                        Log.d(TAG, "Ride request deleted: ${deletedEventId.take(8)}")
                        onDeletion(deletedEventId)
                    }
                }
            }
        }
    }

    /**
     * Subscribe to acceptances for a broadcast ride request.
     * Used by riders to detect when a driver accepts, and by drivers to detect
     * when another driver has accepted (ride is taken).
     *
     * @param offerEventId The ride offer event ID to watch
     * @param onAcceptance Called when acceptance is received
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptancesForOffer(
        offerEventId: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_ACCEPTANCE),
            tags = mapOf("e" to listOf(offerEventId))
        ) { event, _ ->
            RideAcceptanceEvent.parse(event)?.let { data ->
                Log.d(TAG, "Received acceptance for offer ${offerEventId.take(8)} from driver ${data.driverPubKey.take(8)}")
                onAcceptance(data)
            }
        }
    }

    /**
     * Subscribe to ride acceptances for a specific direct offer.
     * Use subscribeToAcceptancesForOffer() for broadcast offers that accept any driver.
     *
     * @param offerEventId The offer event ID to watch
     * @param expectedDriverPubKey The driver pubkey this direct offer was sent to (required)
     * @param onAcceptance Called when acceptance is received from expected driver
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptance(
        offerEventId: String,
        expectedDriverPubKey: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_ACCEPTANCE),
            tags = mapOf("e" to listOf(offerEventId))
        ) { event, _ ->
            RideAcceptanceEvent.parse(event, expectedDriverPubKey = expectedDriverPubKey)?.let { data ->
                onAcceptance(data)
            }
        }
    }

    // ==================== Ride Confirmation (Kind 3175) ====================

    /**
     * Confirm a ride with precise pickup location (encrypted).
     * @param acceptance The driver's acceptance
     * @param precisePickup Precise pickup location (will be encrypted)
     * @param paymentHash HTLC payment hash for escrow verification (moved from offer for correct timing)
     * @param escrowToken Optional escrow token (for cross-mint bridge)
     * @return The event ID if successful, null on failure
     */
    suspend fun confirmRide(
        acceptance: RideAcceptanceData,
        precisePickup: Location,
        paymentHash: String? = null,
        escrowToken: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot confirm ride: Not logged in")
            return null
        }

        return try {
            val event = RideConfirmationEvent.create(
                signer = signer,
                acceptanceEventId = acceptance.eventId,
                driverPubKey = acceptance.driverPubKey,
                precisePickup = precisePickup,
                paymentHash = paymentHash,
                escrowToken = escrowToken
            )
            relayManager.publish(event)
            Log.d(TAG, "Confirmed ride: ${event.id}${paymentHash?.let { " with payment hash" } ?: ""}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm ride", e)
            null
        }
    }

    /**
     * Subscribe to ride confirmations for a specific acceptance (driver listens for rider's confirmation).
     * Automatically decrypts the precise pickup location.
     *
     * @param acceptanceEventId The acceptance event ID to watch
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param expectedRiderPubKey The rider pubkey from the offer (required for validation)
     * @param onConfirmation Called when confirmation is received (with decrypted location)
     * @return Subscription ID for closing later
     */
    fun subscribeToConfirmation(
        acceptanceEventId: String,
        scope: CoroutineScope,
        expectedRiderPubKey: String,
        onConfirmation: (RideConfirmationData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_CONFIRMATION),
            tags = mapOf("e" to listOf(acceptanceEventId))
        ) { event, _ ->
            // Parse encrypted confirmation, validating sender matches expected rider
            RideConfirmationEvent.parseEncrypted(event, expectedRiderPubKey = expectedRiderPubKey)?.let { encryptedData ->
                // Decrypt using driver's key
                val signer = keyManager.getSigner()
                if (signer != null) {
                    scope.launch {
                        RideConfirmationEvent.decrypt(signer, encryptedData)?.let { data ->
                            onConfirmation(data)
                        }
                    }
                }
            }
        }
    }

    // ==================== Driver Ride State (Kind 30180) ====================

    /**
     * Publish or update driver ride state.
     * This is a parameterized replaceable event - only the latest state per ride is kept.
     *
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param riderPubKey The rider's public key
     * @param currentStatus Current status (use DriverStatusType constants)
     * @param history List of all actions in chronological order
     * @param finalFare Final fare in satoshis (for completed rides)
     * @param invoice Lightning invoice (for completed rides)
     * @param lastTransitionId Event ID of last rider state event processed (for chain integrity)
     * @return The event ID if successful, null on failure
     */
    suspend fun publishDriverRideState(
        confirmationEventId: String,
        riderPubKey: String,
        currentStatus: String,
        history: List<DriverRideAction>,
        finalFare: Long? = null,
        invoice: String? = null,
        lastTransitionId: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish driver ride state: Not logged in")
            return null
        }

        return try {
            val event = DriverRideStateEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                currentStatus = currentStatus,
                history = history,
                finalFare = finalFare,
                invoice = invoice,
                lastTransitionId = lastTransitionId
            )
            relayManager.publish(event)
            Log.d(TAG, "Published driver ride state: ${event.id} ($currentStatus, ${history.size} actions)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish driver ride state", e)
            null
        }
    }

    /**
     * Subscribe to driver ride state updates for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param driverPubKey The driver's public key (to filter by author)
     * @param onState Called when state update is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToDriverRideState(
        confirmationEventId: String,
        driverPubKey: String,
        onState: (DriverRideStateData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 30180 events from the driver for this ride
        // d-tag is the confirmation event ID
        // NOTE: No timestamp filter - handlers validate event's confirmationEventId against current ride
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_RIDE_STATE),
            authors = listOf(driverPubKey),
            tags = mapOf("d" to listOf(confirmationEventId))
        ) { event, _ ->
            Log.d(TAG, "Received driver ride state ${event.id} from ${event.pubKey.take(8)}")
            DriverRideStateEvent.parse(event, expectedDriverPubKey = driverPubKey)?.let { data ->
                onState(data)
            }
        }
    }

    // ==================== Rider Ride State (Kind 30181) ====================

    /**
     * Publish or update rider ride state.
     * This is a parameterized replaceable event - only the latest state per ride is kept.
     *
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param driverPubKey The driver's public key
     * @param currentPhase Current phase (use RiderRideStateEvent.Phase constants)
     * @param history List of all actions in chronological order
     * @param lastTransitionId Event ID of last driver state event processed (for chain integrity)
     * @return The event ID if successful, null on failure
     */
    suspend fun publishRiderRideState(
        confirmationEventId: String,
        driverPubKey: String,
        currentPhase: String,
        history: List<RiderRideAction>,
        lastTransitionId: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish rider ride state: Not logged in")
            return null
        }

        return try {
            val event = RiderRideStateEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentPhase,
                history = history,
                lastTransitionId = lastTransitionId
            )
            relayManager.publish(event)
            Log.d(TAG, "Published rider ride state: ${event.id} ($currentPhase, ${history.size} actions)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish rider ride state", e)
            null
        }
    }

    /**
     * Subscribe to rider ride state updates for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param riderPubKey The rider's public key (to filter by author)
     * @param onState Called when state update is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToRiderRideState(
        confirmationEventId: String,
        riderPubKey: String,
        onState: (RiderRideStateData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 30181 events from the rider for this ride
        // d-tag is the confirmation event ID
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDER_RIDE_STATE),
            authors = listOf(riderPubKey),
            tags = mapOf("d" to listOf(confirmationEventId))
        ) { event, _ ->
            Log.d(TAG, "Received rider ride state ${event.id} from ${event.pubKey.take(8)}")
            RiderRideStateEvent.parse(event, expectedRiderPubKey = riderPubKey)?.let { data ->
                onState(data)
            }
        }
    }

    // ==================== Chat (Kind 3178) ====================

    /**
     * Send a private chat message to the other party in a ride.
     * Uses simple NIP-44 encryption (no gift wrapping) for reliability.
     * Messages should be deleted via NIP-09 after ride completes.
     *
     * @param confirmationEventId The ride confirmation event ID
     * @param recipientPubKey The recipient's public key (driver or rider)
     * @param message The message text
     * @return The event ID if successful, null on failure
     */
    suspend fun sendChatMessage(
        confirmationEventId: String,
        recipientPubKey: String,
        message: String
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send chat message: Not logged in")
            return null
        }

        return try {
            // Create encrypted chat message (NIP-44)
            val event = RideshareChatEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                recipientPubKey = recipientPubKey,
                message = message
            )

            relayManager.publish(event)
            Log.d(TAG, "Sent chat message: ${event.id} to ${recipientPubKey.take(8)}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat message", e)
            null
        }
    }

    /**
     * Subscribe to private chat messages for the current user.
     * Automatically decrypts NIP-44 encrypted messages.
     *
     * @param confirmationEventId The ride confirmation event ID to filter by (optional)
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param onMessage Called when a chat message is received (decrypted)
     * @return Subscription ID for closing later
     */
    fun subscribeToChatMessages(
        confirmationEventId: String? = null,
        scope: CoroutineScope,
        onMessage: (RideshareChatData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 3178 chat events addressed to us
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDESHARE_CHAT),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, _ ->
            Log.d(TAG, "Received chat event ${event.id} from ${event.pubKey.take(8)}")

            // Decrypt the message
            val signer = keyManager.getSigner()
            if (signer != null) {
                scope.launch {
                    try {
                        RideshareChatEvent.parseAndDecrypt(signer, event)?.let { chatData ->
                            // Filter by confirmation event if specified
                            if (confirmationEventId == null ||
                                chatData.confirmationEventId == confirmationEventId) {
                                Log.d(TAG, "Decrypted chat message: ${chatData.message.take(20)}...")
                                onMessage(chatData)
                            } else {
                                Log.d(TAG, "Ignoring chat for different ride: ${chatData.confirmationEventId.take(8)}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decrypt chat message", e)
                    }
                }
            }
        }
    }

    // ==================== Cancellation (Kind 3179) ====================

    /**
     * Publish a ride cancellation event.
     * @param confirmationEventId The ride confirmation event ID being cancelled
     * @param otherPartyPubKey The other party's public key (to notify them)
     * @param reason Optional reason for cancellation
     * @return The event ID if successful, null on failure
     */
    suspend fun publishRideCancellation(
        confirmationEventId: String,
        otherPartyPubKey: String,
        reason: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish cancellation: Not logged in")
            return null
        }

        return try {
            val event = RideCancellationEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                otherPartyPubKey = otherPartyPubKey,
                reason = reason
            )
            relayManager.publish(event)
            Log.d(TAG, "Published ride cancellation: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish cancellation", e)
            null
        }
    }

    /**
     * Subscribe to ride cancellation events for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param onCancellation Called when cancellation is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToCancellation(
        confirmationEventId: String,
        onCancellation: (RideCancellationData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 3179 events addressed to us for this ride
        // Filter by both p tag (recipient) and e tag (confirmation) for reliable delivery
        // NOTE: No timestamp filter - handlers validate event's confirmationEventId against current ride
        return relayManager.subscribe(
            kinds = listOf(RideCancellationEvent.KIND),
            tags = mapOf(
                "p" to listOf(myPubKey),
                "e" to listOf(confirmationEventId)
            )
        ) { event, _ ->
            Log.d(TAG, "Received cancellation event ${event.id} from ${event.pubKey.take(8)}")
            RideCancellationEvent.parse(event)?.let { data ->
                onCancellation(data)
            }
        }
    }
}
