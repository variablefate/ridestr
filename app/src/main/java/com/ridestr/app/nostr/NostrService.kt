package com.ridestr.app.nostr

import android.content.Context
import android.util.Log
import com.ridestr.app.nostr.events.*
import com.ridestr.app.nostr.keys.KeyManager
import com.ridestr.app.nostr.relay.RelayConfig
import com.ridestr.app.nostr.relay.RelayConnectionState
import com.ridestr.app.nostr.relay.RelayManager
import kotlinx.coroutines.flow.StateFlow

/**
 * High-level facade for all Nostr operations in the rideshare app.
 *
 * This service provides a simplified API for:
 * - Key management (via KeyManager)
 * - Relay connections (via RelayManager)
 * - Publishing rideshare events
 * - Subscribing to rideshare events
 */
class NostrService(context: Context) {

    companion object {
        private const val TAG = "NostrService"
    }

    val keyManager = KeyManager(context)
    val relayManager = RelayManager(RelayConfig.DEFAULT_RELAYS)

    /**
     * Connection states for all relays.
     */
    val connectionStates: StateFlow<Map<String, RelayConnectionState>> = relayManager.connectionStates

    /**
     * Connect to all configured relays.
     */
    fun connect() {
        Log.d(TAG, "Connecting to relays")
        relayManager.connectAll()
    }

    /**
     * Disconnect from all relays.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from relays")
        relayManager.disconnectAll()
    }

    /**
     * Check if the user is logged in (has a key).
     */
    fun isLoggedIn(): Boolean = keyManager.hasKey()

    /**
     * Check if connected to at least one relay.
     */
    fun isConnected(): Boolean = relayManager.isConnected()

    // ==================== Driver Operations ====================

    /**
     * Broadcast driver availability.
     * @param location Current driver location
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastAvailability(location: Location): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast availability: Not logged in")
            return null
        }

        return try {
            val event = DriverAvailabilityEvent.create(signer, location)
            relayManager.publish(event)
            Log.d(TAG, "Broadcast availability: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast availability", e)
            null
        }
    }

    /**
     * Broadcast driver going offline.
     * @param lastLocation Last known location (for context)
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastOffline(lastLocation: Location): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast offline: Not logged in")
            return null
        }

        return try {
            val event = DriverAvailabilityEvent.createOffline(signer, lastLocation)
            relayManager.publish(event)
            Log.d(TAG, "Broadcast offline: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast offline", e)
            null
        }
    }

    /**
     * Request deletion of events (NIP-09).
     * Used to clean up old availability events to prevent relay spam.
     *
     * @param eventIds List of event IDs to request deletion of
     * @param reason Optional reason for deletion
     * @return The deletion event ID if successful, null on failure
     */
    suspend fun deleteEvents(eventIds: List<String>, reason: String = ""): String? {
        if (eventIds.isEmpty()) return null

        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot delete events: Not logged in")
            return null
        }

        return try {
            val event = DeletionEvent.create(signer, eventIds, reason)
            relayManager.publish(event)
            Log.d(TAG, "Requested deletion of ${eventIds.size} event(s): ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request event deletion", e)
            null
        }
    }

    /**
     * Request deletion of a single event (NIP-09).
     *
     * @param eventId The event ID to request deletion of
     * @param reason Optional reason for deletion
     * @return The deletion event ID if successful, null on failure
     */
    suspend fun deleteEvent(eventId: String, reason: String = ""): String? {
        return deleteEvents(listOf(eventId), reason)
    }

    /**
     * Accept a ride offer.
     * @param offer The offer to accept
     * @return The event ID if successful, null on failure
     */
    suspend fun acceptRide(offer: RideOfferData): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot accept ride: Not logged in")
            return null
        }

        return try {
            val event = RideAcceptanceEvent.create(
                signer = signer,
                offerEventId = offer.eventId,
                riderPubKey = offer.riderPubKey
            )
            relayManager.publish(event)
            Log.d(TAG, "Accepted ride: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept ride", e)
            null
        }
    }

    /**
     * Send a driver status update.
     * @param confirmationEventId The ride confirmation event ID
     * @param riderPubKey The rider's public key
     * @param status Current status (use DriverStatusType constants)
     * @param location Current location (optional)
     * @param finalFare Final fare for completed rides (optional)
     * @param invoice Lightning invoice for payment (optional)
     * @return The event ID if successful, null on failure
     */
    suspend fun sendStatusUpdate(
        confirmationEventId: String,
        riderPubKey: String,
        status: String,
        location: Location? = null,
        finalFare: Double? = null,
        invoice: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send status update: Not logged in")
            return null
        }

        return try {
            val event = DriverStatusEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                status = status,
                location = location,
                finalFare = finalFare,
                invoice = invoice
            )
            relayManager.publish(event)
            Log.d(TAG, "Sent status update: ${event.id} ($status)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send status update", e)
            null
        }
    }

    // ==================== Rider Operations ====================

    /**
     * Send a ride offer to a driver.
     * @param driverAvailability The driver's availability event
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Estimated fare in sats
     * @return The event ID if successful, null on failure
     */
    suspend fun sendRideOffer(
        driverAvailability: DriverAvailabilityData,
        pickup: Location,
        destination: Location,
        fareEstimate: Double
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send ride offer: Not logged in")
            return null
        }

        return try {
            val event = RideOfferEvent.create(
                signer = signer,
                driverAvailabilityEventId = driverAvailability.eventId,
                driverPubKey = driverAvailability.driverPubKey,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate
            )
            relayManager.publish(event)
            Log.d(TAG, "Sent ride offer: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ride offer", e)
            null
        }
    }

    /**
     * Confirm a ride with precise pickup location (encrypted).
     * @param acceptance The driver's acceptance
     * @param precisePickup Precise pickup location (will be encrypted)
     * @return The event ID if successful, null on failure
     */
    suspend fun confirmRide(
        acceptance: RideAcceptanceData,
        precisePickup: Location
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
                precisePickup = precisePickup
            )
            relayManager.publish(event)
            Log.d(TAG, "Confirmed ride: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm ride", e)
            null
        }
    }

    // ==================== Subscriptions ====================

    /**
     * Subscribe to available drivers in the area.
     * @param onDriver Called when a driver availability event is received
     * @return Subscription ID for closing later
     */
    fun subscribeToDrivers(
        onDriver: (DriverAvailabilityData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_AVAILABILITY),
            tags = mapOf("t" to listOf(RideshareTags.RIDESHARE_TAG))
        ) { event, _ ->
            DriverAvailabilityEvent.parse(event)?.let { data ->
                onDriver(data)
            }
        }
    }

    /**
     * Subscribe to ride offers for the current user (as driver).
     * @param onOffer Called when a ride offer is received
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOffers(
        onOffer: (RideOfferData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, _ ->
            RideOfferEvent.parse(event)?.let { data ->
                onOffer(data)
            }
        }
    }

    /**
     * Subscribe to ride acceptances for a specific offer.
     * @param offerEventId The offer event ID to watch
     * @param onAcceptance Called when acceptance is received
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptance(
        offerEventId: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_ACCEPTANCE),
            tags = mapOf("e" to listOf(offerEventId))
        ) { event, _ ->
            RideAcceptanceEvent.parse(event)?.let { data ->
                onAcceptance(data)
            }
        }
    }

    /**
     * Subscribe to driver status updates for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param onStatus Called when status update is received
     * @return Subscription ID for closing later
     */
    fun subscribeToStatusUpdates(
        confirmationEventId: String,
        onStatus: (DriverStatusData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_STATUS),
            tags = mapOf("e" to listOf(confirmationEventId))
        ) { event, _ ->
            DriverStatusEvent.parse(event)?.let { data ->
                onStatus(data)
            }
        }
    }

    /**
     * Close a subscription.
     */
    fun closeSubscription(subscriptionId: String) {
        relayManager.closeSubscription(subscriptionId)
    }

    // ==================== Profile Operations ====================

    /**
     * Publish user profile (metadata).
     * @param profile The user profile to publish
     * @return The event ID if successful, null on failure
     */
    suspend fun publishProfile(profile: UserProfile): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish profile: Not logged in")
            return null
        }

        return try {
            val event = MetadataEvent.create(signer, profile)
            relayManager.publish(event)
            Log.d(TAG, "Published profile: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile", e)
            null
        }
    }

    /**
     * Subscribe to a user's profile updates.
     * @param pubKeyHex The user's public key in hex format
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later
     */
    fun subscribeToProfile(
        pubKeyHex: String,
        onProfile: (UserProfile) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(MetadataEvent.KIND),
            authors = listOf(pubKeyHex),
            limit = 1
        ) { event, _ ->
            MetadataEvent.parse(event)?.let { profile ->
                onProfile(profile)
            }
        }
    }

    /**
     * Subscribe to the current user's own profile.
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOwnProfile(
        onProfile: (UserProfile) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null
        return subscribeToProfile(myPubKey, onProfile)
    }
}
