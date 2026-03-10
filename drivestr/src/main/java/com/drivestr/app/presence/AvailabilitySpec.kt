package com.drivestr.app.presence

import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.DriverAvailabilityEvent
import com.ridestr.common.nostr.events.Location

/** Typed intent for a Kind 30173 availability publish. Only DriverViewModel consumes this. */
internal sealed class AvailabilitySpec {

    /** Full availability: location + vehicle + payment info. Used by startBroadcasting() loop. */
    data class Available(
        val location: Location,
        val vehicle: Vehicle?,
        val mintUrl: String?,
        val paymentMethods: List<String>
    ) : AvailabilitySpec() {
        init { require(paymentMethods.isNotEmpty()) { "paymentMethods must not be empty (upstream coerces to [\"cashu\"])" } }
    }

    /** Locationless AVAILABLE: pubkey-trackable but invisible to geographic search. No vehicle/mint metadata. Used by goRoadflareOnly(). */
    data object RoadflarePresence : AvailabilitySpec()

    /** Offline with location: geographic removal signal. No vehicle/mint metadata. Used by goOffline() from AVAILABLE. */
    data class OfflineWithLocation(val location: Location) : AvailabilitySpec()

    /** Locationless offline: privacy-preserving. No vehicle/mint metadata. Used by goOffline() from ROADFLARE_ONLY and cancelRide(). */
    data object OfflineLocationless : AvailabilitySpec()

    /** Pure mapper → broadcastAvailability() parameters. Testable without Nostr. */
    internal fun toPublishArgs(): AvailabilityPublishArgs = when (this) {
        is Available -> AvailabilityPublishArgs(
            location = location,
            status = DriverAvailabilityEvent.STATUS_AVAILABLE,
            vehicle = vehicle,
            mintUrl = mintUrl,
            paymentMethods = paymentMethods
        )
        is RoadflarePresence -> AvailabilityPublishArgs(
            location = null,
            status = DriverAvailabilityEvent.STATUS_AVAILABLE,
            vehicle = null,
            mintUrl = null,
            paymentMethods = listOf("cashu")
        )
        is OfflineWithLocation -> AvailabilityPublishArgs(
            location = location,
            status = DriverAvailabilityEvent.STATUS_OFFLINE,
            vehicle = null,
            mintUrl = null,
            paymentMethods = listOf("cashu")
        )
        is OfflineLocationless -> AvailabilityPublishArgs(
            location = null,
            status = DriverAvailabilityEvent.STATUS_OFFLINE,
            vehicle = null,
            mintUrl = null,
            paymentMethods = listOf("cashu")
        )
    }
}

/** Parameter bag mirroring broadcastAvailability() signature. Callers must use AvailabilitySpec variants. */
internal data class AvailabilityPublishArgs(
    val location: Location?,
    val status: String,
    val vehicle: Vehicle?,
    val mintUrl: String?,
    val paymentMethods: List<String>
)
