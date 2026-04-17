package com.ridestr.common.coordinator

/**
 * Pure decision logic for pre-confirmation driver availability monitoring.
 *
 * Extracted for testability — coordinators and ViewModels delegate to these functions.
 *
 * Design principle: availability monitoring is pre-acceptance only.
 * Post-acceptance safety relies on Kind 3179 cancellation + post-confirm ack timeout.
 *
 * This is the common-module counterpart of the rider-app [AvailabilityMonitorPolicy].
 * The key difference: the stage parameter is replaced with [isWaitingForAcceptance] so
 * this class has no dependency on the rider-app [RideStage] enum.
 */
internal object AvailabilityMonitorPolicy {

    enum class Action {
        IGNORE,            // Out-of-order, wrong stage, or post-acceptance
        SHOW_UNAVAILABLE,  // Used by coordinator after grace period expires with no acceptance
        DEFER_CHECK        // Offline or deletion during waiting — re-check after grace period
    }

    /** React to a Kind 30173 availability event. */
    fun onAvailabilityEvent(
        isWaitingForAcceptance: Boolean,
        isAvailable: Boolean,
        eventCreatedAt: Long,
        lastSeenTimestamp: Long
    ): Action {
        if (eventCreatedAt < lastSeenTimestamp) return Action.IGNORE
        if (!isWaitingForAcceptance) return Action.IGNORE
        return if (isAvailable) Action.IGNORE else Action.DEFER_CHECK
    }

    /** React to a Kind 5 deletion of driver availability. */
    fun onDeletionEvent(
        isWaitingForAcceptance: Boolean,
        deletionTimestamp: Long,
        lastSeenTimestamp: Long
    ): Action {
        if (deletionTimestamp < lastSeenTimestamp) return Action.IGNORE
        if (!isWaitingForAcceptance) return Action.IGNORE
        return Action.DEFER_CHECK
    }

    /** Seed the timestamp guard. Returns current epoch-seconds when no anchor exists. */
    fun seedTimestamp(initialAvailabilityTimestamp: Long): Long {
        return if (initialAvailabilityTimestamp > 0L) {
            initialAvailabilityTimestamp
        } else {
            System.currentTimeMillis() / 1000
        }
    }
}
