package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30181: Rider Ride State Event (Parameterized Replaceable)
 *
 * Consolidates all rider actions during a ride into a single event with embedded history.
 * Replaces the separate Kind 3177 (Pickup Verification) and Kind 3181 (Precise Location Reveal) events.
 *
 * As a parameterized replaceable event with d-tag set to the ride confirmation ID,
 * relays keep only the latest state per ride per rider.
 *
 * History actions:
 * - "location_reveal": Rider shares precise location (encrypted with NIP-44 to driver)
 * - "pin_verify": Rider verifies PIN submitted by driver
 */
object RiderRideStateEvent {

    /**
     * Action types for rider ride state history.
     */
    object ActionType {
        const val LOCATION_REVEAL = "location_reveal"
        const val PIN_VERIFY = "pin_verify"
        const val PREIMAGE_SHARE = "preimage_share"  // Payment rails: shares escrow preimage
        const val BRIDGE_COMPLETE = "bridge_complete"  // Cross-mint: confirms Lightning bridge payment
    }

    /**
     * Location types for location reveal actions.
     */
    object LocationType {
        const val PICKUP = "pickup"
        const val DESTINATION = "destination"
    }

    /**
     * Current phase values for rider state.
     */
    object Phase {
        const val AWAITING_DRIVER = "awaiting_driver"
        const val AWAITING_PIN = "awaiting_pin"
        const val VERIFIED = "verified"
        const val IN_RIDE = "in_ride"
    }

    /**
     * Create and sign a rider ride state event with embedded history.
     *
     * @param signer The rider's signer
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param driverPubKey The driver's public key
     * @param currentPhase The current phase (awaiting_driver, awaiting_pin, verified, in_ride)
     * @param history List of all actions in chronological order
     * @param lastTransitionId Event ID of last driver state event processed (for chain integrity)
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        driverPubKey: String,
        currentPhase: String,
        history: List<RiderRideAction>,
        lastTransitionId: String? = null
    ): Event {
        // Build history array
        val historyArray = JSONArray()
        history.forEach { action ->
            historyArray.put(action.toJson())
        }

        val content = JSONObject().apply {
            put("current_phase", currentPhase)
            put("history", historyArray)
        }.toString()

        // Add NIP-40 expiration (8 hours)
        val expiration = RideshareExpiration.hoursFromNow(RideshareExpiration.PRECISE_LOCATION_HOURS)

        // Build tags list, conditionally including transition tag for chain integrity
        val tagsList = mutableListOf(
            // d-tag required for parameterized replaceable - use confirmation ID
            arrayOf("d", confirmationEventId),
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        // Add transition tag if we have a prior driver state event (AtoB pattern)
        lastTransitionId?.let {
            tagsList.add(arrayOf("transition", it))
        }

        val tags = tagsList.toTypedArray()

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDER_RIDE_STATE,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a rider ride state event to extract current phase and history.
     */
    fun parse(event: Event): RiderRideStateData? {
        if (event.kind != RideshareEventKinds.RIDER_RIDE_STATE) return null

        return try {
            val json = JSONObject(event.content)
            val currentPhase = json.getString("current_phase")

            // Parse history array
            val history = mutableListOf<RiderRideAction>()
            if (json.has("history")) {
                val historyArray = json.getJSONArray("history")
                for (i in 0 until historyArray.length()) {
                    RiderRideAction.fromJson(historyArray.getJSONObject(i))?.let {
                        history.add(it)
                    }
                }
            }

            // Extract tags
            var confirmationEventId: String? = null
            var driverPubKey: String? = null
            var lastTransitionId: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    "d" -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                    "transition" -> lastTransitionId = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || driverPubKey == null) return null

            RiderRideStateData(
                eventId = event.id,
                riderPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentPhase,
                history = history,
                createdAt = event.createdAt,
                lastTransitionId = lastTransitionId
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Helper to create a location reveal action.
     * Location is encrypted with NIP-44 before calling this.
     */
    fun createLocationRevealAction(
        locationType: String,
        locationEncrypted: String
    ): RiderRideAction.LocationReveal {
        return RiderRideAction.LocationReveal(
            locationType = locationType,
            locationEncrypted = locationEncrypted,
            at = System.currentTimeMillis() / 1000
        )
    }

    /**
     * Helper to create a PIN verify action.
     */
    fun createPinVerifyAction(
        verified: Boolean,
        attempt: Int
    ): RiderRideAction.PinVerify {
        return RiderRideAction.PinVerify(
            verified = verified,
            attempt = attempt,
            at = System.currentTimeMillis() / 1000
        )
    }

    /**
     * Helper to create a preimage share action.
     * Preimage and escrow token are encrypted with NIP-44 to driver before calling this.
     * Called automatically after successful PIN verification.
     *
     * @param preimageEncrypted NIP-44 encrypted preimage (64-char hex)
     * @param escrowTokenEncrypted NIP-44 encrypted HTLC token containing locked funds
     */
    fun createPreimageShareAction(
        preimageEncrypted: String,
        escrowTokenEncrypted: String? = null
    ): RiderRideAction.PreimageShare {
        return RiderRideAction.PreimageShare(
            preimageEncrypted = preimageEncrypted,
            escrowTokenEncrypted = escrowTokenEncrypted,
            at = System.currentTimeMillis() / 1000
        )
    }

    /**
     * Helper to create a bridge complete action.
     * Called after successful cross-mint Lightning bridge payment.
     *
     * @param preimage Lightning payment preimage (64-char hex, proves payment)
     * @param amountSats Amount paid in satoshis
     * @param feesSats Fees paid (melt fee + Lightning routing)
     */
    fun createBridgeCompleteAction(
        preimage: String,
        amountSats: Long,
        feesSats: Long
    ): RiderRideAction.BridgeComplete {
        return RiderRideAction.BridgeComplete(
            preimage = preimage,
            amountSats = amountSats,
            feesSats = feesSats,
            at = System.currentTimeMillis() / 1000
        )
    }
}

/**
 * Sealed class for rider ride actions.
 */
sealed class RiderRideAction {
    abstract val at: Long

    abstract fun toJson(): JSONObject

    /**
     * Location reveal action.
     * Location is encrypted with NIP-44 to the driver's pubkey.
     */
    data class LocationReveal(
        val locationType: String,  // "pickup" or "destination"
        val locationEncrypted: String,
        override val at: Long
    ) : RiderRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", RiderRideStateEvent.ActionType.LOCATION_REVEAL)
            put("location_type", locationType)
            put("location_encrypted", locationEncrypted)
            put("at", at)
        }
    }

    /**
     * PIN verification action.
     */
    data class PinVerify(
        val verified: Boolean,
        val attempt: Int,
        override val at: Long
    ) : RiderRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", RiderRideStateEvent.ActionType.PIN_VERIFY)
            put("status", if (verified) "verified" else "rejected")
            put("attempt", attempt)
            put("at", at)
        }
    }

    /**
     * Preimage share action.
     * Shares the HTLC preimage and escrow token with the driver after successful PIN verification.
     * Preimage and escrow token are NIP-44 encrypted to the driver's pubkey.
     * This allows the driver to settle the escrow at dropoff.
     *
     * @property preimageEncrypted NIP-44 encrypted preimage (64-char hex that SHA256-hashes to paymentHash)
     * @property escrowTokenEncrypted NIP-44 encrypted HTLC token containing locked funds (optional for legacy)
     */
    data class PreimageShare(
        val preimageEncrypted: String,
        val escrowTokenEncrypted: String? = null,
        override val at: Long
    ) : RiderRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", RiderRideStateEvent.ActionType.PREIMAGE_SHARE)
            put("preimage_encrypted", preimageEncrypted)
            escrowTokenEncrypted?.let { put("escrow_token_encrypted", it) }
            put("at", at)
        }
    }

    /**
     * Bridge complete action (Cross-Mint).
     * Records successful Lightning bridge payment when rider and driver use different mints.
     * The preimage proves payment was made to driver's mint.
     */
    data class BridgeComplete(
        val preimage: String,       // Lightning payment preimage (proof of payment)
        val amountSats: Long,       // Amount paid to driver's mint
        val feesSats: Long,         // Total fees (melt + routing)
        override val at: Long
    ) : RiderRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", RiderRideStateEvent.ActionType.BRIDGE_COMPLETE)
            put("preimage", preimage)
            put("amount", amountSats)
            put("fees", feesSats)
            put("at", at)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): RiderRideAction? {
            return try {
                val action = json.getString("action")
                val at = json.getLong("at")

                when (action) {
                    RiderRideStateEvent.ActionType.LOCATION_REVEAL -> {
                        val locationType = json.getString("location_type")
                        val locationEncrypted = json.getString("location_encrypted")
                        LocationReveal(
                            locationType = locationType,
                            locationEncrypted = locationEncrypted,
                            at = at
                        )
                    }
                    RiderRideStateEvent.ActionType.PIN_VERIFY -> {
                        val status = json.getString("status")
                        val attempt = json.optInt("attempt", 1)
                        PinVerify(
                            verified = status == "verified",
                            attempt = attempt,
                            at = at
                        )
                    }
                    RiderRideStateEvent.ActionType.PREIMAGE_SHARE -> {
                        val preimageEncrypted = json.getString("preimage_encrypted")
                        val escrowTokenEncrypted = json.optString("escrow_token_encrypted").takeIf { it.isNotEmpty() }
                        PreimageShare(
                            preimageEncrypted = preimageEncrypted,
                            escrowTokenEncrypted = escrowTokenEncrypted,
                            at = at
                        )
                    }
                    RiderRideStateEvent.ActionType.BRIDGE_COMPLETE -> {
                        val preimage = json.getString("preimage")
                        val amount = json.getLong("amount")
                        val fees = json.getLong("fees")
                        BridgeComplete(
                            preimage = preimage,
                            amountSats = amount,
                            feesSats = fees,
                            at = at
                        )
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Parsed data from a rider ride state event.
 */
data class RiderRideStateData(
    val eventId: String,
    val riderPubKey: String,
    val confirmationEventId: String,
    val driverPubKey: String,
    val currentPhase: String,
    val history: List<RiderRideAction>,
    val createdAt: Long,
    /** Event ID of last driver state event that was processed (for chain integrity) */
    val lastTransitionId: String? = null
) {
    fun isAwaitingDriver(): Boolean = currentPhase == RiderRideStateEvent.Phase.AWAITING_DRIVER
    fun isAwaitingPin(): Boolean = currentPhase == RiderRideStateEvent.Phase.AWAITING_PIN
    fun isVerified(): Boolean = currentPhase == RiderRideStateEvent.Phase.VERIFIED
    fun isInRide(): Boolean = currentPhase == RiderRideStateEvent.Phase.IN_RIDE

    /**
     * Get the latest PIN verification from history, if any.
     */
    fun getLatestPinVerification(): RiderRideAction.PinVerify? {
        return history.filterIsInstance<RiderRideAction.PinVerify>().lastOrNull()
    }

    /**
     * Get all location reveals from history.
     */
    fun getLocationReveals(): List<RiderRideAction.LocationReveal> {
        return history.filterIsInstance<RiderRideAction.LocationReveal>()
    }

    /**
     * Get pickup location reveal if present.
     */
    fun getPickupLocationReveal(): RiderRideAction.LocationReveal? {
        return history.filterIsInstance<RiderRideAction.LocationReveal>()
            .lastOrNull { it.locationType == RiderRideStateEvent.LocationType.PICKUP }
    }

    /**
     * Get destination location reveal if present.
     */
    fun getDestinationLocationReveal(): RiderRideAction.LocationReveal? {
        return history.filterIsInstance<RiderRideAction.LocationReveal>()
            .lastOrNull { it.locationType == RiderRideStateEvent.LocationType.DESTINATION }
    }

    /**
     * Check if PIN was successfully verified.
     */
    fun isPinVerified(): Boolean {
        return getLatestPinVerification()?.verified == true
    }

    /**
     * Get the current PIN verification attempt count.
     */
    fun getPinAttemptCount(): Int {
        return history.filterIsInstance<RiderRideAction.PinVerify>().size
    }

    /**
     * Get the preimage share action if present.
     * Used by driver to extract preimage for settlement.
     */
    fun getPreimageShare(): RiderRideAction.PreimageShare? {
        return history.filterIsInstance<RiderRideAction.PreimageShare>().lastOrNull()
    }

    /**
     * Check if preimage has been shared.
     */
    fun isPreimageShared(): Boolean {
        return getPreimageShare() != null
    }

    /**
     * Get the bridge complete action if present.
     * Present when cross-mint Lightning bridge was used for payment.
     */
    fun getBridgeComplete(): RiderRideAction.BridgeComplete? {
        return history.filterIsInstance<RiderRideAction.BridgeComplete>().lastOrNull()
    }

    /**
     * Check if cross-mint bridge payment was completed.
     */
    fun isBridgeComplete(): Boolean {
        return getBridgeComplete() != null
    }
}
