package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.payment.WalletService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Carries the outcome of a successful offer acceptance.
 *
 * @property acceptanceEventId Kind 3174 event ID published to relays.
 * @property offer The offer that was accepted (normalized to [RideOfferData]).
 * @property broadcastRequest The original broadcast request, or null for direct offers.
 * @property walletPubKey Driver's wallet public key used for HTLC locking, or null if no wallet.
 * @property driverMintUrl Driver's Cashu mint URL, or null if wallet is not configured.
 * @property paymentPath Resolved payment path (SAME_MINT, CROSS_MINT, FIAT_CASH, NO_PAYMENT).
 */
data class AcceptanceResult(
    val acceptanceEventId: String,
    val offer: RideOfferData,
    val broadcastRequest: BroadcastRideOfferData?,
    val walletPubKey: String?,
    val driverMintUrl: String?,
    val paymentPath: PaymentPath
)

/**
 * Handles the offer-acceptance protocol for direct and broadcast ride offers.
 *
 * Responsibilities:
 * - Publish Kind 3174 acceptance events via [NostrService]
 * - Resolve wallet public key and mint URL for HTLC parameter handshake
 * - Derive [PaymentPath] from rider/driver mint URLs and payment method
 * - First-acceptance-wins gating for broadcast offers (AtomicBoolean CAS)
 *
 * The coordinator performs only protocol I/O; all UI-state mutations and subscription
 * management remain in the ViewModel. Unit-testable without Android context.
 *
 * // TODO(#52): convert constructor injection to @Inject once Hilt migration lands
 */
class AcceptanceCoordinator(
    private val nostrService: NostrService,
    private val walletServiceProvider: () -> WalletService?
) {

    companion object {
        private const val TAG = "AcceptanceCoordinator"
    }

    /**
     * CAS gate for broadcast offers: only the first caller proceeds; all others are dropped.
     * Reset via [resetBroadcastGate] when returning to AVAILABLE after a ride ends or times out.
     */
    private val hasAcceptedBroadcast = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Direct offer acceptance
    // -------------------------------------------------------------------------

    /**
     * Accept a direct ride offer (Kind 3173 → Kind 3174).
     *
     * @param offer The offer received from the rider.
     * @return [AcceptanceResult] on success, null if the Nostr publish failed.
     */
    suspend fun acceptOffer(offer: RideOfferData): AcceptanceResult? {
        val walletPubKey = walletServiceProvider()?.getWalletPubKey()
        val driverMintUrl = walletServiceProvider()?.getSavedMintUrl()

        Log.d(TAG, "Accepting direct offer ${offer.eventId.take(8)} from ${offer.riderPubKey.take(8)}")

        val eventId = nostrService.acceptRide(
            offer = offer,
            walletPubKey = walletPubKey,
            mintUrl = driverMintUrl,
            paymentMethod = offer.paymentMethod
        ) ?: run {
            Log.e(TAG, "acceptRide returned null — Nostr publish failed")
            return null
        }

        val paymentPath = PaymentPath.determine(offer.mintUrl, driverMintUrl, offer.paymentMethod)
        Log.d(TAG, "Direct acceptance OK: $eventId (paymentPath=$paymentPath)")

        return AcceptanceResult(
            acceptanceEventId = eventId,
            offer = offer,
            broadcastRequest = null,
            walletPubKey = walletPubKey,
            driverMintUrl = driverMintUrl,
            paymentPath = paymentPath
        )
    }

    // -------------------------------------------------------------------------
    // Broadcast offer acceptance
    // -------------------------------------------------------------------------

    /**
     * Accept a broadcast ride request (Kind 3173 broadcast → Kind 3174).
     *
     * Uses a CAS gate ([hasAcceptedBroadcast]) to ensure only one acceptance is published
     * even when multiple callbacks fire concurrently from different relay connections.
     *
     * @param request The broadcast request received from the rider.
     * @param myPubKey The driver's own Nostr public key (used to build the compatible offer).
     * @return [AcceptanceResult] on success, null if already accepted by another thread or
     *   if the Nostr publish failed.
     */
    suspend fun acceptBroadcastRequest(
        request: BroadcastRideOfferData,
        myPubKey: String
    ): AcceptanceResult? {
        if (!hasAcceptedBroadcast.compareAndSet(false, true)) {
            Log.d(TAG, "acceptBroadcastRequest: CAS gate blocked duplicate acceptance")
            return null
        }

        val walletPubKey = walletServiceProvider()?.getWalletPubKey()
        val driverMintUrl = walletServiceProvider()?.getSavedMintUrl()

        Log.d(TAG, "Accepting broadcast request ${request.eventId.take(8)} from ${request.riderPubKey.take(8)}")

        val eventId = nostrService.acceptBroadcastRide(
            request = request,
            walletPubKey = walletPubKey,
            mintUrl = driverMintUrl,
            paymentMethod = request.paymentMethod
        ) ?: run {
            Log.e(TAG, "acceptBroadcastRide returned null — Nostr publish failed")
            hasAcceptedBroadcast.set(false) // allow retry
            return null
        }

        // Normalize to RideOfferData for the shared downstream ride flow
        val compatibleOffer = RideOfferData(
            eventId = request.eventId,
            riderPubKey = request.riderPubKey,
            driverEventId = "",
            driverPubKey = myPubKey,
            approxPickup = request.pickupArea,
            destination = request.destinationArea,
            fareEstimate = request.fareEstimate,
            createdAt = request.createdAt,
            mintUrl = request.mintUrl,
            paymentMethod = request.paymentMethod,
            fiatFare = request.fiatFare
        )

        val paymentPath = PaymentPath.determine(request.mintUrl, driverMintUrl, request.paymentMethod)
        Log.d(TAG, "Broadcast acceptance OK: $eventId (paymentPath=$paymentPath)")

        return AcceptanceResult(
            acceptanceEventId = eventId,
            offer = compatibleOffer,
            broadcastRequest = request,
            walletPubKey = walletPubKey,
            driverMintUrl = driverMintUrl,
            paymentPath = paymentPath
        )
    }

    /**
     * Reset the broadcast first-acceptance gate.
     *
     * Call this when the driver returns to AVAILABLE (ride completed, timed out, or cancelled)
     * so the next broadcast offer can be accepted.
     */
    fun resetBroadcastGate() {
        hasAcceptedBroadcast.set(false)
    }
}
