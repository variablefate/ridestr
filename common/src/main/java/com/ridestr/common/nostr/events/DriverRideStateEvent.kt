package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30180: Driver Ride State Event (Parameterized Replaceable)
 *
 * Consolidates all driver actions during a ride into a single event with embedded history.
 * Replaces the separate Kind 3180 (Driver Status) and Kind 3176 (PIN Submission) events.
 *
 * As a parameterized replaceable event with d-tag set to the ride confirmation ID,
 * relays keep only the latest state per ride per driver.
 *
 * History actions:
 * - "status": Driver status update (en_route_pickup, arrived, in_progress, completed, cancelled)
 * - "pin_submit": Driver submits PIN (encrypted with NIP-44 to rider)
 */
object DriverRideStateEvent {

    /**
     * Action types for driver ride state history.
     */
    object ActionType {
        const val STATUS = "status"
        const val PIN_SUBMIT = "pin_submit"
        const val SETTLEMENT = "settlement"  // Payment rails: records successful escrow settlement
        const val DEPOSIT_INVOICE_SHARE = "deposit_invoice_share"  // Cross-mint: driver shares deposit invoice
    }

    /**
     * Create and sign a driver ride state event with embedded history.
     *
     * @param signer The driver's signer
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param riderPubKey The rider's public key
     * @param currentStatus The current status (en_route_pickup, arrived, in_progress, completed, cancelled)
     * @param history List of all actions in chronological order
     * @param finalFare Final fare in satoshis (for completed rides)
     * @param invoice Lightning invoice (for completed rides)
     * @param lastTransitionId Event ID of last rider state event processed (for chain integrity)
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        riderPubKey: String,
        currentStatus: String,
        history: List<DriverRideAction>,
        finalFare: Long? = null,
        invoice: String? = null,
        lastTransitionId: String? = null
    ): Event {
        // Build history array
        val historyArray = JSONArray()
        history.forEach { action ->
            historyArray.put(action.toJson())
        }

        val content = JSONObject().apply {
            put("current_status", currentStatus)
            put("history", historyArray)
            finalFare?.let { put("final_fare", it) }
            invoice?.let { put("invoice", it) }
        }.toString()

        // Add NIP-40 expiration (8 hours)
        val expiration = RideshareExpiration.hoursFromNow(RideshareExpiration.DRIVER_STATUS_HOURS)

        // Build tags list, conditionally including transition tag for chain integrity
        val tagsList = mutableListOf(
            // d-tag required for parameterized replaceable - use confirmation ID
            arrayOf("d", confirmationEventId),
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, riderPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        // Add transition tag if we have a prior rider state event (AtoB pattern)
        lastTransitionId?.let {
            tagsList.add(arrayOf("transition", it))
        }

        val tags = tagsList.toTypedArray()

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.DRIVER_RIDE_STATE,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a driver ride state event to extract current status and history.
     */
    fun parse(event: Event): DriverRideStateData? {
        if (event.kind != RideshareEventKinds.DRIVER_RIDE_STATE) return null

        return try {
            val json = JSONObject(event.content)
            val currentStatus = json.getString("current_status")
            val finalFare = if (json.has("final_fare")) json.getLong("final_fare") else null
            val invoice = if (json.has("invoice")) json.getString("invoice") else null

            // Parse history array
            val history = mutableListOf<DriverRideAction>()
            if (json.has("history")) {
                val historyArray = json.getJSONArray("history")
                for (i in 0 until historyArray.length()) {
                    DriverRideAction.fromJson(historyArray.getJSONObject(i))?.let {
                        history.add(it)
                    }
                }
            }

            // Extract tags
            var confirmationEventId: String? = null
            var riderPubKey: String? = null
            var lastTransitionId: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    "d" -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> riderPubKey = tag.getOrNull(1)
                    "transition" -> lastTransitionId = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || riderPubKey == null) return null

            DriverRideStateData(
                eventId = event.id,
                driverPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                currentStatus = currentStatus,
                history = history,
                finalFare = finalFare,
                invoice = invoice,
                createdAt = event.createdAt,
                lastTransitionId = lastTransitionId
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Helper to create a status action.
     */
    fun createStatusAction(
        status: String,
        location: Location? = null,
        finalFare: Long? = null,
        invoice: String? = null
    ): DriverRideAction.Status {
        return DriverRideAction.Status(
            status = status,
            approxLocation = location?.approximate(),
            finalFare = finalFare,
            invoice = invoice,
            at = System.currentTimeMillis() / 1000
        )
    }

    /**
     * Helper to create a PIN submit action.
     * PIN is encrypted with NIP-44 before calling this.
     */
    fun createPinSubmitAction(encryptedPin: String): DriverRideAction.PinSubmit {
        return DriverRideAction.PinSubmit(
            pinEncrypted = encryptedPin,
            at = System.currentTimeMillis() / 1000
        )
    }

    /**
     * Helper to create a settlement action.
     * Called when driver successfully settles the HTLC escrow at dropoff.
     *
     * @param settlementProof Cryptographic proof of settlement (e.g., HTLC claim tx)
     * @param settledAmount Amount settled in satoshis
     */
    fun createSettlementAction(
        settlementProof: String,
        settledAmount: Long
    ): DriverRideAction.Settlement {
        return DriverRideAction.Settlement(
            settlementProof = settlementProof,
            settledAmount = settledAmount,
            at = System.currentTimeMillis() / 1000
        )
    }
}

/**
 * Sealed class for driver ride actions.
 */
sealed class DriverRideAction {
    abstract val at: Long

    abstract fun toJson(): JSONObject

    /**
     * Status update action.
     */
    data class Status(
        val status: String,
        val approxLocation: Location? = null,
        val finalFare: Long? = null,
        val invoice: String? = null,
        override val at: Long
    ) : DriverRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", DriverRideStateEvent.ActionType.STATUS)
            put("status", status)
            put("at", at)
            approxLocation?.let { put("approx_location", it.toJson()) }
            finalFare?.let { put("final_fare", it) }
            invoice?.let { put("invoice", it) }
        }
    }

    /**
     * PIN submission action.
     * PIN is encrypted with NIP-44 to the rider's pubkey.
     */
    data class PinSubmit(
        val pinEncrypted: String,
        override val at: Long
    ) : DriverRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", DriverRideStateEvent.ActionType.PIN_SUBMIT)
            put("pin_encrypted", pinEncrypted)
            put("at", at)
        }
    }

    /**
     * Settlement action.
     * Records successful HTLC escrow settlement at dropoff.
     */
    data class Settlement(
        val settlementProof: String,
        val settledAmount: Long,
        override val at: Long
    ) : DriverRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", DriverRideStateEvent.ActionType.SETTLEMENT)
            put("settlement_proof", settlementProof)
            put("settled_amount", settledAmount)
            put("at", at)
        }
    }

    /**
     * Deposit invoice share action (Cross-Mint).
     * Driver shares their mint's deposit invoice with rider for Lightning bridge payment.
     */
    data class DepositInvoiceShare(
        val invoice: String,           // BOLT11 invoice from driver's mint
        val amount: Long,              // Amount in satoshis
        override val at: Long
    ) : DriverRideAction() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("action", DriverRideStateEvent.ActionType.DEPOSIT_INVOICE_SHARE)
            put("invoice", invoice)
            put("amount", amount)
            put("at", at)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): DriverRideAction? {
            return try {
                val action = json.getString("action")
                val at = json.getLong("at")

                when (action) {
                    DriverRideStateEvent.ActionType.STATUS -> {
                        val status = json.getString("status")
                        val approxLocation = if (json.has("approx_location")) {
                            Location.fromJson(json.getJSONObject("approx_location"))
                        } else null
                        val finalFare = if (json.has("final_fare")) json.getLong("final_fare") else null
                        val invoice = if (json.has("invoice")) json.getString("invoice") else null

                        Status(
                            status = status,
                            approxLocation = approxLocation,
                            finalFare = finalFare,
                            invoice = invoice,
                            at = at
                        )
                    }
                    DriverRideStateEvent.ActionType.PIN_SUBMIT -> {
                        val pinEncrypted = json.getString("pin_encrypted")
                        PinSubmit(pinEncrypted = pinEncrypted, at = at)
                    }
                    DriverRideStateEvent.ActionType.SETTLEMENT -> {
                        val settlementProof = json.getString("settlement_proof")
                        val settledAmount = json.getLong("settled_amount")
                        Settlement(
                            settlementProof = settlementProof,
                            settledAmount = settledAmount,
                            at = at
                        )
                    }
                    DriverRideStateEvent.ActionType.DEPOSIT_INVOICE_SHARE -> {
                        val invoice = json.getString("invoice")
                        val amount = json.getLong("amount")
                        DepositInvoiceShare(
                            invoice = invoice,
                            amount = amount,
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
 * Parsed data from a driver ride state event.
 */
data class DriverRideStateData(
    val eventId: String,
    val driverPubKey: String,
    val confirmationEventId: String,
    val riderPubKey: String,
    val currentStatus: String,
    val history: List<DriverRideAction>,
    val finalFare: Long?,
    val invoice: String?,
    val createdAt: Long,
    /** Event ID of last rider state event that was processed (for chain integrity) */
    val lastTransitionId: String? = null
) {
    fun isCompleted(): Boolean = currentStatus == DriverStatusType.COMPLETED
    fun isCancelled(): Boolean = currentStatus == DriverStatusType.CANCELLED
    fun isArrived(): Boolean = currentStatus == DriverStatusType.ARRIVED
    fun isInProgress(): Boolean = currentStatus == DriverStatusType.IN_PROGRESS
    fun isEnRoutePickup(): Boolean = currentStatus == DriverStatusType.EN_ROUTE_PICKUP

    /**
     * Get the latest PIN submission from history, if any.
     */
    fun getLatestPinSubmission(): DriverRideAction.PinSubmit? {
        return history.filterIsInstance<DriverRideAction.PinSubmit>().lastOrNull()
    }

    /**
     * Get all status updates from history.
     */
    fun getStatusUpdates(): List<DriverRideAction.Status> {
        return history.filterIsInstance<DriverRideAction.Status>()
    }

    /**
     * Get the settlement action if present.
     */
    fun getSettlement(): DriverRideAction.Settlement? {
        return history.filterIsInstance<DriverRideAction.Settlement>().lastOrNull()
    }

    /**
     * Check if the ride has been settled (payment completed).
     */
    fun isSettled(): Boolean {
        return getSettlement() != null
    }

    /**
     * Get the settled amount if payment was completed.
     */
    fun getSettledAmount(): Long? {
        return getSettlement()?.settledAmount
    }
}
