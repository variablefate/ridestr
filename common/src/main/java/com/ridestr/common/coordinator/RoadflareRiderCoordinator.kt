package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RoadflareKeyShareEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow

private const val TAG = "RoadflareRiderCoord"

/**
 * Events emitted by [RoadflareRiderCoordinator] describing outcomes of rider-side
 * RoadFlare protocol flows.
 */
sealed class RoadflareRiderEvent {
    /** A driver has sent us their RoadFlare key (Kind 3186). */
    data class KeyReceived(val driverPubKey: String) : RoadflareRiderEvent()

    /** A key share event could not be verified or the driver is unknown. */
    data class KeyShareIgnored(val reason: String) : RoadflareRiderEvent()
}

/**
 * Centralises the rider-side RoadFlare protocol flows that were previously
 * scattered across [rider-app MainActivity] and [RoadflareTab]:
 *
 * - Kind 3186 key share reception — driver sends encrypted RoadFlare key to rider
 * - Kind 3188 key acknowledgement sending — rider acknowledges a received key,
 *   or requests a refresh for a stale one
 *
 * Kind 3189 driver ping sending is tracked by Issue #52 and will be added once
 * NostrService exposes a `publishDriverPing()` method.
 *
 * **Lifecycle:** create in a ViewModel init block, call [startKeyShareListener], call
 * [destroy] from `onCleared()`.
 *
 * **DI note:** constructor injection is manual (no Hilt) until the Hilt migration
 * tracked in Issue #52.
 */
// TODO(#52): convert to @Singleton @Inject
class RoadflareRiderCoordinator(
    private val nostrService: NostrService,
    private val followedDriversRepository: FollowedDriversRepository,
    private val scope: CoroutineScope
) {

    // ── Events ────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<RoadflareRiderEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Hot stream of [RoadflareRiderEvent] describing protocol outcomes.
     * Collectors receive events only while subscribed; older events are dropped
     * if the buffer overflows.
     */
    val events: SharedFlow<RoadflareRiderEvent> = _events.asSharedFlow()

    // ── Private state ─────────────────────────────────────────────────────────

    private var keyShareSubId: String? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Subscribe to incoming Kind 3186 RoadFlare key share events.
     *
     * For each event received the coordinator will:
     * 1. Parse and decrypt the payload using the rider's signer.
     * 2. Validate that the `driverPubKey` field in the payload matches
     *    the event's author pubkey (guards against relay substitution).
     * 3. Verify the sending driver is in [FollowedDriversRepository.drivers].
     * 4. Persist the key via [FollowedDriversRepository.updateDriverKey].
     * 5. Publish a Kind 3188 acknowledgement.
     * 6. Emit [RoadflareRiderEvent.KeyReceived] or [RoadflareRiderEvent.KeyShareIgnored].
     *
     * Calling this while a subscription is already active is a no-op; call
     * [stopKeyShareListener] first if you need to restart.
     */
    fun startKeyShareListener() {
        if (keyShareSubId != null) {
            Log.d(TAG, "Key share listener already active, skipping start")
            return
        }

        keyShareSubId = nostrService.subscribeToRoadflareKeyShares { event, _ ->
            scope.launch {
                try {
                    val signer = nostrService.keyManager.getSigner()
                    if (signer == null) {
                        Log.w(TAG, "Kind 3186 received but no signer available, ignoring")
                        _events.emit(RoadflareRiderEvent.KeyShareIgnored("no signer"))
                        return@launch
                    }

                    val data = RoadflareKeyShareEvent.parseAndDecrypt(signer, event)
                    if (data == null) {
                        Log.d(TAG, "Kind 3186 parse/decrypt returned null for eventId=${event.id.take(8)}")
                        _events.emit(RoadflareRiderEvent.KeyShareIgnored("parse/decrypt failed"))
                        return@launch
                    }

                    // Guard: payload driverPubKey must match the event author.
                    if (data.driverPubKey != event.pubKey) {
                        Log.w(
                            TAG,
                            "Kind 3186 driverPubKey mismatch: payload=${data.driverPubKey.take(8)} " +
                                "!= event=${event.pubKey.take(8)}"
                        )
                        _events.emit(RoadflareRiderEvent.KeyShareIgnored("driverPubKey mismatch"))
                        return@launch
                    }

                    // Guard: only accept keys from known followed drivers.
                    val existingDriver = followedDriversRepository.drivers.value
                        .find { it.pubkey == data.driverPubKey }
                    if (existingDriver == null) {
                        Log.d(TAG, "Kind 3186 from unknown driver ${data.driverPubKey.take(8)}, ignoring")
                        _events.emit(RoadflareRiderEvent.KeyShareIgnored("driver unknown"))
                        return@launch
                    }

                    val updatedKey = data.roadflareKey.copy(keyUpdatedAt = data.keyUpdatedAt)
                    followedDriversRepository.updateDriverKey(data.driverPubKey, updatedKey)

                    nostrService.publishRoadflareKeyAck(
                        driverPubKey = data.driverPubKey,
                        keyVersion = data.roadflareKey.version,
                        keyUpdatedAt = data.keyUpdatedAt
                    )

                    Log.d(TAG, "Kind 3186 processed for driver ${data.driverPubKey.take(8)}")
                    _events.emit(RoadflareRiderEvent.KeyReceived(data.driverPubKey))
                } catch (e: Exception) {
                    Log.e(TAG, "Kind 3186 processing error", e)
                    _events.emit(RoadflareRiderEvent.KeyShareIgnored("exception: ${e.message}"))
                }
            }
        }

        Log.d(TAG, "Key share listener started (subId=${keyShareSubId?.take(8)})")
    }

    /**
     * Stop the Kind 3186 key share subscription started by [startKeyShareListener].
     * Safe to call when no subscription is active.
     */
    fun stopKeyShareListener() {
        keyShareSubId?.let {
            nostrService.closeRoadflareSubscription(it)
            Log.d(TAG, "Key share listener stopped (subId=${it.take(8)})")
        }
        keyShareSubId = null
    }

    // TODO(#52): `sendKeyAck()` and `pingDriver()` will move here once the live callers
    // (MainActivity, RoadflareTab) are migrated through the coordinator. Today they publish
    // Kind 3188 / 3189 directly via `NostrService.publishRoadflareKeyAck()` so wrapping them in
    // the coordinator would just be dead surface.
    // TODO(#52): add `pingDriver()` once NostrService exposes a `publishDriverPing()` method.
    // The coordinator class-level KDoc lists this capability. The method was deliberately
    // omitted until the underlying publisher exists, to avoid a stub that lies to callers by
    // emitting PingSent for a no-op.

    /**
     * Stop all subscriptions. Call from the owning ViewModel's `onCleared()`.
     * Does NOT cancel [scope] — the coordinator does not own the scope lifecycle.
     */
    fun destroy() {
        stopKeyShareListener()
        Log.d(TAG, "Destroyed")
    }
}
