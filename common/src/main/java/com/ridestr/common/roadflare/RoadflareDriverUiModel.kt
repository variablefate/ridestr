package com.ridestr.common.roadflare

/**
 * Shared UI model for RoadFlare driver cards, used by both apps.
 *
 * @param formattedFare Pre-formatted fare string (respects displayCurrency). Null when CALCULATING.
 * @param fareState Controls card display: CALCULATING shows "Fare calculating...",
 *   EXACT shows fare, FALLBACK shows fare + "Approx.", ESTIMATED shows fare + "Est. fare".
 * @param isBroadcastEligible online + hasKey + !tooFar + fresh + fareState != CALCULATING
 * @param isDirectSelectable hasKey + !isSending + fareState != CALCULATING
 */
data class RoadflareDriverUiModel(
    val pubkey: String,
    val displayName: String,
    val status: DriverStatus,
    val formattedFare: String?,
    val pickupMiles: Double?,
    val fareState: FareState,
    val isTooFar: Boolean,
    val isBroadcastEligible: Boolean,
    val isDirectSelectable: Boolean
) {
    enum class DriverStatus {
        AVAILABLE,
        TOO_FAR,
        OFFLINE,
        PENDING_APPROVAL,
        ON_RIDE
    }
}
