# Ridestr State Machines

**Version**: 1.0
**Last Updated**: 2026-01-15

This document defines the state machines for both Rider and Driver applications.

---

## RiderStage State Machine

The rider's ride lifecycle is managed by `RiderStage` enum in `RiderViewModel`.

### States

| State | Description |
|-------|-------------|
| `IDLE` | No active ride, browsing drivers |
| `WAITING_FOR_ACCEPTANCE` | Direct offer sent, waiting for driver response |
| `BROADCASTING_REQUEST` | Broadcast request sent, waiting for any driver |
| `DRIVER_ACCEPTED` | Driver accepted, auto-confirming ride |
| `RIDE_CONFIRMED` | Ride confirmed, driver en route |
| `DRIVER_ARRIVED` | Driver at pickup, waiting for PIN verification |
| `IN_PROGRESS` | Ride in progress after PIN verified |
| `COMPLETED` | Ride finished successfully |

### State Diagram

```
                                    +-----------------------+
                                    |                       |
                                    v                       |
+------+    sendRideOffer()    +------------------------+   |
| IDLE |---------------------->| WAITING_FOR_ACCEPTANCE |   |
+------+                       +------------------------+   |
    |                                    |                  |
    |                                    | timeout/cancel   |
    |                                    +------------------+
    |                                    |
    |                                    | acceptance received
    |                                    v
    |    broadcastRideRequest()   +------------------------+
    +----------------------------->| BROADCASTING_REQUEST  |
                                  +------------------------+
                                           |
                                           | timeout/cancel
                                           +------------------+
                                           |                  |
                                           | acceptance       |
                                           v                  |
                                  +----------------+          |
                                  | DRIVER_ACCEPTED|<---------+
                                  +----------------+
                                           |
                                           | autoConfirmRide()
                                           v
                                  +----------------+
                                  | RIDE_CONFIRMED |
                                  +----------------+
                                           |
                                           | driver status: ARRIVED
                                           v
                                  +----------------+
                                  | DRIVER_ARRIVED |
                                  +----------------+
                                           |
                                           | PIN verified successfully
                                           v
                                  +----------------+
                                  |  IN_PROGRESS   |
                                  +----------------+
                                           |
                                           | driver status: COMPLETED
                                           v
                                  +----------------+
                                  |   COMPLETED    |
                                  +----------------+
                                           |
                                           | clearRide()
                                           v
                                       +------+
                                       | IDLE |
                                       +------+
```

### Transitions

| From | Trigger | To |
|------|---------|-----|
| IDLE | `sendRideOffer()` | WAITING_FOR_ACCEPTANCE |
| IDLE | `broadcastRideRequest()` | BROADCASTING_REQUEST |
| WAITING_FOR_ACCEPTANCE | Acceptance event (Kind 3174) | DRIVER_ACCEPTED |
| WAITING_FOR_ACCEPTANCE | 15s timeout / user cancel / driver unavailable | IDLE |
| BROADCASTING_REQUEST | Acceptance event (Kind 3174) | DRIVER_ACCEPTED |
| BROADCASTING_REQUEST | 2min timeout / user cancel | IDLE |
| DRIVER_ACCEPTED | `autoConfirmRide()` (automatic) | RIDE_CONFIRMED |
| RIDE_CONFIRMED | Driver state: ARRIVED | DRIVER_ARRIVED |
| DRIVER_ARRIVED | PIN verified (rider state action) | IN_PROGRESS |
| IN_PROGRESS | Driver state: COMPLETED | COMPLETED |
| COMPLETED | `clearRide()` | IDLE |
| Any active state | Driver cancellation (Kind 3179) | IDLE |
| Any active state | User cancellation | IDLE |

### Cancellation Points

| State | User Can Cancel? | Effect |
|-------|-----------------|--------|
| IDLE | N/A | No ride to cancel |
| WAITING_FOR_ACCEPTANCE | Yes | Return to IDLE, no harm (also auto-cancels if driver goes unavailable) |
| BROADCASTING_REQUEST | Yes | Return to IDLE, no harm |
| DRIVER_ACCEPTED | Yes | Publish cancellation, return to IDLE |
| RIDE_CONFIRMED | Yes | Publish cancellation, return to IDLE |
| DRIVER_ARRIVED | Yes | Publish cancellation, return to IDLE |
| IN_PROGRESS | Yes | Publish cancellation, return to IDLE |
| COMPLETED | No | Ride is over |

---

## DriverStage State Machine

The driver's ride lifecycle is managed by `DriverStage` enum in `DriverViewModel`.

### States

| State | Description |
|-------|-------------|
| `OFFLINE` | Not accepting rides |
| `AVAILABLE` | Online and accepting ride requests |
| `RIDE_ACCEPTED` | Accepted a ride, awaiting confirmation |
| `EN_ROUTE_TO_PICKUP` | Driving to pickup location |
| `ARRIVED_AT_PICKUP` | At pickup, waiting for passenger |
| `IN_RIDE` | Passenger in car, driving to destination |
| `RIDE_COMPLETED` | Ride finished |

### State Diagram

```
+---------+     goOnline()     +-----------+
| OFFLINE |<------------------>| AVAILABLE |
+---------+     goOffline()    +-----------+
                                    |
                                    | acceptBroadcastRequest() /
                                    | acceptOffer()
                                    v
                              +---------------+
                              | RIDE_ACCEPTED |
                              +---------------+
                                    |
                                    | confirmation received (Kind 3175)
                                    | + automatic transition
                                    v
                            +--------------------+
                            | EN_ROUTE_TO_PICKUP |
                            +--------------------+
                                    |
                                    | arrivedAtPickup()
                                    v
                            +--------------------+
                            | ARRIVED_AT_PICKUP  |
                            +--------------------+
                                    |
                                    | PIN verified (rider state)
                                    v
                              +-----------+
                              |  IN_RIDE  |
                              +-----------+
                                    |
                                    | completeRide()
                                    v
                            +----------------+
                            | RIDE_COMPLETED |
                            +----------------+
                                    |
                                    | clearAcceptedOffer()
                                    v
                               +---------+
                               | OFFLINE |
                               +---------+
```

### Transitions

| From | Trigger | To |
|------|---------|-----|
| OFFLINE | `goOnline()` | AVAILABLE |
| AVAILABLE | `goOffline()` | OFFLINE |
| AVAILABLE | `acceptBroadcastRequest()` | RIDE_ACCEPTED |
| AVAILABLE | `acceptOffer()` | RIDE_ACCEPTED |
| RIDE_ACCEPTED | Confirmation (Kind 3175) + auto | EN_ROUTE_TO_PICKUP |
| EN_ROUTE_TO_PICKUP | `arrivedAtPickup()` | ARRIVED_AT_PICKUP |
| ARRIVED_AT_PICKUP | Rider PIN verify success | IN_RIDE |
| IN_RIDE | `completeRide()` | RIDE_COMPLETED |
| RIDE_COMPLETED | `clearAcceptedOffer()` | OFFLINE |
| Any active state | Rider cancellation (Kind 3179) | AVAILABLE |
| Any active state | `cancelRide()` | AVAILABLE |

### Driver Actions by State

| State | Available Actions |
|-------|-------------------|
| OFFLINE | Go Online |
| AVAILABLE | Go Offline, Accept Offer, Accept Broadcast |
| RIDE_ACCEPTED | Cancel Ride |
| EN_ROUTE_TO_PICKUP | Arrived at Pickup, Cancel Ride |
| ARRIVED_AT_PICKUP | Submit PIN, Cancel Ride |
| IN_RIDE | Complete Ride |
| RIDE_COMPLETED | Clear (return to offline) |

---

## PIN Verification Flow

PIN verification spans both state machines with the following coordination:

```
RIDER APP                                    DRIVER APP
---------                                    ----------

[DRIVER_ARRIVED stage]                       [ARRIVED_AT_PICKUP stage]
     |                                              |
     |  Displays PIN to rider                       |
     |  "Tell driver: 4821"                         |
     |                                              |
     |                                              |
     |                              Rider verbally tells PIN
     |                                              |
     |                                              v
     |                                       [Driver enters PIN]
     |                                              |
     |                                              v
     |                               Driver publishes Kind 30180
     |                               with PIN_SUBMIT action
     |                                              |
     v                                              |
Receives Kind 30180 <-------------------------------+
     |
     v
Validates PIN:
- If match: Publish Kind 30181 (pin_verify: true)
- If wrong: Publish Kind 30181 (pin_verify: false, attempt: N)
     |
     +---> After 3 failures: Auto-cancel ride
     |
     v
If verified:
- Stage -> IN_PROGRESS
- Reveal destination location
- Publish Kind 30181 (location_reveal: destination)
     |
     |
     +-----------------------------------> Receives Kind 30181
                                                   |
                                                   v
                                           PIN verified:
                                           - Stage -> IN_RIDE
                                           - Show destination

```

### PIN Security

- 4-digit random PIN generated per ride
- Maximum 3 verification attempts
- After 3 failures, ride is automatically cancelled
- PIN shared verbally in person (not via app)
- Encrypted in all Nostr events

---

## Subscription Lifecycle

Each state has associated Nostr subscriptions:

### Rider Subscriptions by State

| State | Active Subscriptions |
|-------|---------------------|
| IDLE | Driver availability (Kind 30173) |
| WAITING_FOR_ACCEPTANCE | + Acceptance (Kind 3174) |
| BROADCASTING_REQUEST | + Acceptance (Kind 3174) |
| DRIVER_ACCEPTED | + Driver ride state (Kind 30180), Chat (Kind 3178), Cancellation (Kind 3179) |
| RIDE_CONFIRMED | Same as DRIVER_ACCEPTED |
| DRIVER_ARRIVED | Same as DRIVER_ACCEPTED |
| IN_PROGRESS | Same as DRIVER_ACCEPTED |
| COMPLETED | All ride subscriptions closed |

### Driver Subscriptions by State

| State | Active Subscriptions |
|-------|---------------------|
| OFFLINE | None |
| AVAILABLE | Ride offers (Kind 3173), Broadcast requests (Kind 3173 with g-tag) |
| RIDE_ACCEPTED | + Rider ride state (Kind 30181), Confirmation (Kind 3175), Chat (Kind 3178), Cancellation (Kind 3179) |
| EN_ROUTE_TO_PICKUP | Same as RIDE_ACCEPTED |
| ARRIVED_AT_PICKUP | Same as RIDE_ACCEPTED |
| IN_RIDE | Same as RIDE_ACCEPTED |
| RIDE_COMPLETED | All ride subscriptions closed |

---

## Error States and Recovery

### Network Disconnection
- Both apps persist ride state to SharedPreferences
- On reconnect, state is restored and subscriptions reestablished
- Stale drivers (>10 min) are automatically removed from list

### Ride State Mismatch
- Each event validates `confirmationEventId` matches current ride
- Event deduplication prevents stale events from affecting new rides
- `processedDriverStateEventIds` and `processedCancellationEventIds` track processed events

### Timeout Handling
- Direct offers: 15 second timeout
- Broadcast requests: 2 minute timeout
- HODL invoices (future): 2-3 hour expiry
- All events have NIP-40 expiration tags

---

## State Machine Implementation Notes

### RiderViewModel State Fields

```kotlin
// Primary stage
rideStage: RideStage

// Ride identification
confirmationEventId: String?  // Canonical ride ID
acceptance: RideAcceptanceData?

// Driver info
selectedDriver: DriverAvailabilityData?
driverProfiles: Map<String, UserProfile>

// Locations
pickupLocation: Location?
destinationLocation: Location?

// PIN
pickupPin: String?
pinFailedAttempts: Int

// Subscriptions (managed separately)
driverRideStateSubscriptionId: String?
cancellationSubscriptionId: String?
chatSubscriptionId: String?
```

### DriverViewModel State Fields

```kotlin
// Primary stage
stage: DriverStage

// Ride identification
confirmationEventId: String?
acceptedOffer: RideOfferData?

// Rider info
riderPubKey: String?
riderProfile: UserProfile?

// Locations (revealed progressively)
pickupLocation: Location?  // After acceptance
precisePickupLocation: Location?  // After 1 mile
destinationLocation: Location?  // After PIN

// Subscriptions (managed separately)
riderRideStateSubscriptionId: String?
cancellationSubscriptionId: String?
chatSubscriptionId: String?
```
