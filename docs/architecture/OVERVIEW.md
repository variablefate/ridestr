# Ridestr Architecture Overview

**Version**: 1.0
**Last Updated**: 2026-01-15

---

## System Overview

Ridestr is a decentralized rideshare application built on the Nostr protocol. It consists of:

- **Rider App** (rider-app): Android app for passengers
- **Driver App** (drivestr): Android app for drivers
- **Common Module** (common): Shared code for Nostr, routing, and data

```
┌─────────────────────────────────────────────────────────────────┐
│                        RIDESTR SYSTEM                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────────┐         ┌───────────────┐                   │
│  │  Rider App    │         │  Driver App   │                   │
│  │  (rider-app)  │         │  (drivestr)   │                   │
│  └───────┬───────┘         └───────┬───────┘                   │
│          │                         │                            │
│          └──────────┬──────────────┘                            │
│                     │                                           │
│          ┌──────────┴──────────┐                                │
│          │   Common Module     │                                │
│          │   - NostrService    │                                │
│          │   - Routing         │                                │
│          │   - Event Types     │                                │
│          └──────────┬──────────┘                                │
│                     │                                           │
│          ┌──────────┴──────────┐                                │
│          │   Nostr Relays      │                                │
│          │   (decentralized)   │                                │
│          └─────────────────────┘                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### NostrService

**File**: `common/src/main/java/com/ridestr/common/nostr/NostrService.kt`

Central service for all Nostr operations:
- Connection management to relays
- Event publishing (all 9 event kinds)
- Subscription management
- NIP-44 encryption/decryption
- Profile fetching

### RiderViewModel

**File**: `rider-app/src/main/java/com/ridestr/rider/viewmodels/RiderViewModel.kt`

Manages rider state machine:
- 8 ride stages (IDLE through COMPLETED)
- 30+ state fields
- 6 subscription types
- ~2800 lines

See: [RIDER_VIEWMODEL.md](../viewmodels/RIDER_VIEWMODEL.md)

### DriverViewModel

**File**: `drivestr/src/main/java/com/drivestr/app/viewmodels/DriverViewModel.kt`

Manages driver state machine:
- 7 ride stages (OFFLINE through RIDE_COMPLETED)
- 35+ state fields
- 8 subscription types
- ~2800 lines

See: [DRIVER_VIEWMODEL.md](../viewmodels/DRIVER_VIEWMODEL.md)

---

## Data Flow

### Ride Lifecycle

```
1. DISCOVERY
   Driver → publishes Kind 30173 (availability)
   Rider ← subscribes to nearby drivers

2. REQUEST
   Rider → publishes Kind 3173 (offer/broadcast)
   Driver ← receives offer

3. ACCEPTANCE
   Driver → publishes Kind 3174 (acceptance)
   Rider ← receives acceptance

4. CONFIRMATION
   Rider → publishes Kind 3175 (confirmation + PIN)
   Driver ← receives confirmation
   [Confirmation event ID becomes canonical ride identifier]

5. EN ROUTE
   Driver → publishes Kind 30180 (status: EN_ROUTE)
   Rider ← receives status update
   Rider → publishes Kind 30181 (precise pickup when <1 mile)

6. ARRIVAL
   Driver → publishes Kind 30180 (status: ARRIVED)
   Rider ← receives arrival notification

7. PIN VERIFICATION
   Driver → publishes Kind 30180 (PIN submission)
   Rider ← verifies PIN
   Rider → publishes Kind 30181 (verification result + destination)

8. IN PROGRESS
   Driver → publishes Kind 30180 (status: IN_PROGRESS)
   Both ↔ Kind 3178 (chat messages)

9. COMPLETION
   Driver → publishes Kind 30180 (status: COMPLETED)
   Both → publish Kind 30174 (ride history backup)
```

---

## Key Design Decisions

### 1. Parameterized Replaceable Events

Kinds 30173, 30180, 30181, 30174 use NIP-33 parameterized replaceable events:
- Single source of truth per ride
- Accumulating history in one event
- Simpler state management
- Efficient relay storage

### 2. Progressive Location Reveal

Location privacy protection:
- Offer: ~150m geohash only
- Acceptance: ~150m geohash only
- En route: Precise pickup when driver <1 mile
- PIN verified: Precise destination revealed

### 3. Event Deduplication

To prevent stale events from affecting new rides:
```kotlin
private val processedDriverStateEventIds = mutableSetOf<String>()
private val processedCancellationEventIds = mutableSetOf<String>()
```

### 4. NIP-40 Expiration

All events have expiration tags to prevent relay bloat:
- Availability: 10 minutes
- Offers: 15 seconds to 2 minutes
- Ride state: 3 hours
- History: 7 days

---

## Current Complexity

| Component | Lines | Concern |
|-----------|-------|---------|
| RiderViewModel | ~2800 | Too large, hard to navigate |
| DriverViewModel | ~2800 | Too large, hard to navigate |
| NostrService | ~1500 | Manageable |
| **Total** | ~7100 | Needs simplification |

### Simplification Opportunities

1. **RideSession Object**: Consolidate 30+ scattered fields
2. **SubscriptionManager**: Extract subscription lifecycle
3. **EventDeduplicator**: Move to NostrService level
4. **State Machine Enforcement**: Prevent invalid transitions

See: [Simplification Plan](../../.claude/plans/memoized-baking-newt.md)

---

## Future: Payment Rails

Payment integration will add:
- `paymentHash` in ride offers
- HODL invoices or Nut-14 HTLC proofs
- Preimage sharing via encrypted Nostr event
- Geohash-gated fund claiming

See: [Payment Flow Plan](../../.claude/plans/paymentrails.md)

---

## Documentation Index

### Protocol
- [NOSTR_EVENTS.md](../protocol/NOSTR_EVENTS.md) - All event kind definitions

### Architecture
- [STATE_MACHINES.md](./STATE_MACHINES.md) - State diagrams and transitions
- [OVERVIEW.md](./OVERVIEW.md) - This file

### ViewModels
- [RIDER_VIEWMODEL.md](../viewmodels/RIDER_VIEWMODEL.md) - Rider function reference
- [DRIVER_VIEWMODEL.md](../viewmodels/DRIVER_VIEWMODEL.md) - Driver function reference

### Payment (Future)
- Payment flow documentation will be added during implementation
