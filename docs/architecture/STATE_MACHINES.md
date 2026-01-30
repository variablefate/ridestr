# Ridestr State Machines

**Version**: 1.1
**Last Updated**: 2026-01-30

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
| `ROADFLARE_ONLY` | Broadcasting RoadFlare location (Kind 30014, not Kind 30173), receiving only RoadFlare-tagged offers |
| `AVAILABLE` | Online and accepting all ride requests |
| `RIDE_ACCEPTED` | Accepted a ride, awaiting confirmation |
| `EN_ROUTE_TO_PICKUP` | Driving to pickup location |
| `ARRIVED_AT_PICKUP` | At pickup, waiting for passenger |
| `IN_RIDE` | Passenger in car, driving to destination |
| `RIDE_COMPLETED` | Ride finished |

### State Diagram

```
+---------+  goRoadflareOnly()  +----------------+  goOnline()  +-----------+
| OFFLINE |-------------------->| ROADFLARE_ONLY |------------->| AVAILABLE |
+---------+<--------------------+----------------+<-------------+-----------+
     ^       goOffline()              ^              goOffline()      |
     |                                |                               |
     +--------------------------------+-------------------------------+
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
| OFFLINE | `goRoadflareOnly()` | ROADFLARE_ONLY |
| ROADFLARE_ONLY | `goOnline()` | AVAILABLE |
| ROADFLARE_ONLY | `goOffline()` | OFFLINE |
| ROADFLARE_ONLY | `acceptOffer()` (RoadFlare) | RIDE_ACCEPTED |
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
| OFFLINE | Go Online (All Rides), Go Online (RoadFlare Only) |
| ROADFLARE_ONLY | Go Fully Online, Go Offline, Accept RoadFlare Offers |
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
| ROADFLARE_ONLY | RoadFlare offers only (Kind 3173 with roadflare tag) |
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

---

## Offer Types and State Machine Entry

Three ways to enter the ride state machine, each with different discovery and privacy characteristics:

### Offer Type Comparison

| Type | Privacy | Entry Point (Rider) | Entry Point (Driver) |
|------|---------|---------------------|---------------------|
| **Direct Offer** | High (encrypted) | `sendRideOffer()` | `acceptOffer()` |
| **Broadcast Offer** | Medium (~1km approx) | `broadcastRideRequest()` | `acceptBroadcastRequest()` |
| **RoadFlare Offer** | High (encrypted) | `sendRoadflareOffer()` | `acceptOffer()` (isRoadflare=true) |

### Direct Offer Flow
```
Rider: IDLE → [sendRideOffer()] → WAITING_FOR_ACCEPTANCE
Driver: AVAILABLE → [acceptOffer()] → RIDE_ACCEPTED
```

### Broadcast Offer Flow
```
Rider: IDLE → [broadcastRideRequest()] → BROADCASTING_REQUEST
Driver: AVAILABLE → [acceptBroadcastRequest()] → RIDE_ACCEPTED
Note: First driver acceptance wins
```

### RoadFlare Offer Flow
```
Rider: IDLE → [sendRoadflareOffer()] → WAITING_FOR_ACCEPTANCE
Driver: ROADFLARE_ONLY → [acceptOffer()] → RIDE_ACCEPTED
Note: Requires follower approval + key exchange
```

---

## Payment Flow Integration

Payment (HTLC escrow) is tightly integrated with the state machine:

### Payment Timing

| State Transition | Payment Action |
|-----------------|----------------|
| DRIVER_ACCEPTED | `lockForRide()` - HTLC locked with driver's `wallet_pubkey` |
| RIDE_CONFIRMED | `paymentHash` + `escrowToken` sent in Kind 3175 |
| IN_PROGRESS (after PIN) | `preimage_share` published in Kind 30181 |
| COMPLETED | Driver calls `claimHtlcPayment()` with preimage |

### Deferred HTLC Locking

HTLC is locked AFTER driver acceptance (not in offer):

```
1. Rider sends Kind 3173 (offer WITHOUT paymentHash)
2. Driver sends Kind 3174 (acceptance WITH wallet_pubkey)
3. Rider calls lockForRide(wallet_pubkey) → creates HTLC
4. Rider sends Kind 3175 (confirmation WITH paymentHash + escrowToken)
```

This ensures HTLC is locked to the correct driver wallet key.

### HTLC Preimage Storage (January 2026)

Preimage is stored in `PendingHtlc` for future-proof refunds:
- `lockForRide()` now accepts `preimage` parameter
- Stored preimage used in `refundExpiredHtlc()` if mint requires it
- Falls back to zeros for old HTLCs without stored preimage

---

## Driver Availability States

When driver is online, they can be in different availability modes:

### Availability Mode vs Ride Stage

| Mode | Kind 30173 | Kind 30014 | Offers Received |
|------|-----------|-----------|-----------------|
| **OFFLINE** | None | None | None |
| **ROADFLARE_ONLY** | No location (privacy) | Broadcasting | Only `roadflare`-tagged |
| **AVAILABLE** | Has location + geohash | Broadcasting | All offers |

### Availability Changes When Accepting Ride

| Event | Kind 30173 | Kind 30014 |
|-------|-----------|-----------|
| Accept ride | Stops + NIP-09 deletion | Continues with `ON_RIDE` status |
| Go offline | Final `OFFLINE` status → deletion | `OFFLINE` status → stops |

**Key insight:** Kind 30014 (RoadFlare location) never stops while driver is online - only the status changes: `ONLINE` → `ON_RIDE` → `OFFLINE`
