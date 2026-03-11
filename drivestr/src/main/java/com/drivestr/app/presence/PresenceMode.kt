package com.drivestr.app.presence

/** Base operational mode of the driver. Owned by the presence layer — no service/notification concerns. */
internal enum class PresenceMode {
    OFF,              // Service not needed (OFFLINE, transient stages)
    ROADFLARE_ONLY,   // Private network only
    AVAILABLE,        // Accepting all offers
    EN_ROUTE,         // Heading to pickup
    AT_PICKUP,        // Arrived at pickup
    IN_RIDE           // Ride in progress
}
