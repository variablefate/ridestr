package com.ridestr.common.notification

import java.io.Serializable

/**
 * Unified alert types for notification stacking.
 * Replaces DriverStackableAlert and StackableAlert with shared base.
 *
 * SORT PRIORITIES (verified against current behavior):
 * - Driver: Chat=0, NewRideRequest=1 (only 2 types)
 * - Rider: Chat=0, Arrived=1, EnRoute=2, Accepted=3 (4 types)
 *
 * Since driver and rider use different subsets, we assign unique priorities
 * to avoid any ordering ambiguity when types are mixed.
 */
sealed interface AlertType : Serializable {
    val sortPriority: Int  // Lower = shown first

    data class Chat(val preview: String) : AlertType {
        override val sortPriority = 0  // Always first (both apps)
    }

    // Driver-only: new ride request alert
    data class NewRideRequest(val fare: String, val distance: String) : AlertType {
        override val sortPriority = 1  // Driver: Chat=0, Request=1
    }

    // Rider status alerts (rider-only, with optional name)
    sealed interface RideStatusAlert : AlertType {
        val participantName: String?
    }

    data class Arrived(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 10  // Rider: most urgent status
    }

    data class EnRoute(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 20
    }

    data class Accepted(override val participantName: String?) : RideStatusAlert {
        override val sortPriority = 30  // Rider: earliest in journey
    }
}
