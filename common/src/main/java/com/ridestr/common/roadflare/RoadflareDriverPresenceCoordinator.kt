package com.ridestr.common.roadflare

import android.util.Log
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "PresenceCoordinator"

/**
 * ViewModel-scoped coordinator that manages RoadFlare location subscriptions
 * and driver profile fetching. Replaces tab-scoped subscription code
 * (DriverNetworkTab.kt / RoadflareTab.kt / RiderModeScreen.kt).
 *
 * Lifecycle: created in ViewModel init, start() in init, stop() in onCleared().
 * Subscription stays alive across tab switches — eliminates subscribe/unsubscribe churn.
 */
class RoadflareDriverPresenceCoordinator(
    private val nostrService: NostrService,
    private val followedDriversRepository: FollowedDriversRepository,
    private val scope: CoroutineScope
) {
    private var locationSubId: String? = null
    private val lastLocationCreatedAt = mutableMapOf<String, Long>()

    /**
     * Start collecting followed drivers and subscribing to their locations + profiles.
     * Call from ViewModel init block.
     */
    fun start() {
        scope.launch {
            followedDriversRepository.drivers.collect { drivers ->
                resubscribe(drivers)
                fetchMissingProfiles(drivers)
            }
        }
    }

    private fun resubscribe(drivers: List<FollowedDriver>) {
        locationSubId?.let { nostrService.closeRoadflareSubscription(it) }
        locationSubId = null

        // Issue #82: subscribe to ALL followed drivers, not just those with a current key.
        // The PUBLIC `status` tag on Kind 30014 lets us track availability for stale-key /
        // missing-key drivers without ever decrypting the encrypted lat/lon content. The
        // decryption attempt below is best-effort — if it fails we still surface the driver
        // as available via `updateDriverPresence`, so the rider can fall back to the
        // rider-route fare and request the ride anyway.
        if (drivers.isEmpty()) {
            Log.d(TAG, "No followed drivers to subscribe to")
            return
        }

        val driverPubkeys = drivers.map { it.pubkey }
        val withKeysCount = drivers.count { it.roadflareKey != null }
        Log.d(TAG, "Subscribing to ${driverPubkeys.size} driver locations ($withKeysCount with keys)")

        locationSubId = nostrService.subscribeToRoadflareLocations(driverPubkeys) { event, relayUrl ->
            val driverPubKey = event.pubKey
            val eventCreatedAt = event.createdAt
            val isExpired = RoadflareLocationEvent.isExpired(event)
            val lastSeen = lastLocationCreatedAt[driverPubKey] ?: 0L
            val isOutOfOrder = eventCreatedAt < lastSeen

            if (isExpired || isOutOfOrder) {
                Log.d(TAG, "Rejected stale/out-of-order 30014 from ${driverPubKey.take(8)}: expired=$isExpired, outOfOrder=$isOutOfOrder")
                return@subscribeToRoadflareLocations
            }

            // Mark this event as the latest seen for the driver, regardless of decryption
            // outcome — the out-of-order guard at the top now applies to presence-only
            // events too, so a stale Kind 30014 can't overwrite a fresher presence update.
            lastLocationCreatedAt[driverPubKey] = eventCreatedAt

            // Always update presence from the PUBLIC tags — works regardless of key state.
            val publicStatus = RoadflareLocationEvent.getStatus(event)
            val publicKeyVersion = RoadflareLocationEvent.getKeyVersion(event)
            followedDriversRepository.updateDriverPresence(
                pubkey = driverPubKey,
                status = publicStatus,
                timestamp = eventCreatedAt,
                keyVersion = publicKeyVersion
            )

            // Best-effort location decryption — only succeeds if we have the current key.
            val driver = drivers.find { it.pubkey == driverPubKey }
            val roadflareKey = driver?.roadflareKey
            if (roadflareKey == null) {
                Log.d(TAG, "No RoadFlare key for ${driverPubKey.take(8)} — presence-only update")
                return@subscribeToRoadflareLocations
            }

            val locationData = decryptRoadflareLocation(
                roadflarePrivKey = roadflareKey.privateKey,
                driverPubKey = driverPubKey,
                event = event
            )

            if (locationData != null) {
                followedDriversRepository.updateDriverLocation(
                    pubkey = driverPubKey,
                    lat = locationData.location.lat,
                    lon = locationData.location.lon,
                    status = locationData.tagStatus,
                    timestamp = eventCreatedAt,
                    keyVersion = locationData.keyVersion
                )
            } else {
                // Decryption failed despite having a key — likely stale (key was rotated).
                // Presence already updated above; rider will see the driver as available
                // and fall through to the rider-route fare path on offer-send.
                Log.w(TAG, "Failed to decrypt location from ${driverPubKey.take(8)} (presence-only, likely stale key)")
            }
        }
    }

    private fun fetchMissingProfiles(drivers: List<FollowedDriver>) {
        val names = followedDriversRepository.driverNames.value
        drivers.forEach { driver ->
            if (!names.containsKey(driver.pubkey)) {
                nostrService.subscribeToProfile(driver.pubkey) { profile ->
                    val firstName = (profile.displayName ?: profile.name)
                        ?.split(" ")?.firstOrNull()
                    if (!firstName.isNullOrBlank()) {
                        followedDriversRepository.cacheDriverName(driver.pubkey, firstName)
                    }
                }
            }
        }
    }

    /**
     * Stop all subscriptions. Call from ViewModel onCleared().
     */
    fun stop() {
        locationSubId?.let { nostrService.closeRoadflareSubscription(it) }
        locationSubId = null
        lastLocationCreatedAt.clear()
        // Don't clear presence/locations from the repository here — other ViewModel
        // observers may briefly read them across configuration changes. The repository
        // owns its own clear lifecycle (e.g., on logout).
    }

    companion object {
        /**
         * Decrypt a RoadFlare location event using the shared RoadFlare private key.
         *
         * NIP-44 ECDH math:
         * - Driver encrypted with: ECDH(driver_identity_priv, roadflare_pub)
         * - We decrypt with: ECDH(roadflare_priv, driver_identity_pub)
         * - These produce the same shared secret (ECDH is commutative)
         */
        internal fun decryptRoadflareLocation(
            roadflarePrivKey: String,
            driverPubKey: String,
            event: Event
        ): com.ridestr.common.nostr.events.RoadflareLocationData? {
            return try {
                val keyPair = KeyPair(privKey = roadflarePrivKey.hexToByteArray())
                val tempSigner = NostrSignerInternal(keyPair)

                RoadflareLocationEvent.parseAndDecrypt(
                    roadflarePrivKey = roadflarePrivKey,
                    driverPubKey = driverPubKey,
                    event = event,
                    decryptFn = { ciphertext, counterpartyPubKey ->
                        try {
                            runBlocking {
                                tempSigner.nip44Decrypt(ciphertext, counterpartyPubKey)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "NIP-44 decrypt failed", e)
                            null
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt RoadFlare location", e)
                null
            }
        }
    }
}
