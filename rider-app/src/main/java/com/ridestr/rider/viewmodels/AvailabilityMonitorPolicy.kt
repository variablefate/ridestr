package com.ridestr.rider.viewmodels

/**
 * Pure decision logic for pre-confirmation driver availability monitoring.
 * Extracted for testability — RiderViewModel delegates to these functions.
 *
 * Design principle: availability monitoring is pre-acceptance only.
 * Post-acceptance safety relies on Kind 3179 cancellation + post-confirm ack timeout.
 */
internal object AvailabilityMonitorPolicy {

    enum class Action {
        IGNORE,            // Out-of-order, wrong stage, or post-acceptance
        SHOW_UNAVAILABLE,  // Driver went offline during WAITING_FOR_ACCEPTANCE
        DEFER_CHECK        // Deletion during WAITING_FOR_ACCEPTANCE — re-check after grace period
    }

    /** React to a Kind 30173 availability event. */
    fun onAvailabilityEvent(
        stage: RideStage,
        isAvailable: Boolean,
        eventCreatedAt: Long,
        lastSeenTimestamp: Long
    ): Action {
        if (eventCreatedAt < lastSeenTimestamp) return Action.IGNORE
        if (stage != RideStage.WAITING_FOR_ACCEPTANCE) return Action.IGNORE
        return if (isAvailable) Action.IGNORE else Action.SHOW_UNAVAILABLE
    }

    /** React to a Kind 5 deletion of driver availability. */
    fun onDeletionEvent(
        stage: RideStage,
        deletionTimestamp: Long,
        lastSeenTimestamp: Long
    ): Action {
        if (deletionTimestamp < lastSeenTimestamp) return Action.IGNORE
        if (stage != RideStage.WAITING_FOR_ACCEPTANCE) return Action.IGNORE
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
