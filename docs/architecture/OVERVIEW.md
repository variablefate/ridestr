# Ridestr Architecture Overview

**Version**: 1.2
**Last Updated**: 2026-02-03

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

## Profile Sync Architecture

ProfileSyncManager coordinates all profile data sync with Nostr relays:

### Sync Order (on key import)
1. **Wallet (order=0)** - NIP-60 proofs, highest priority
2. **Unified Profile (order=1)** - Kind 30177 events (vehicles, locations, settings)
3. **Ride History (order=2)** - Kind 30174 events

### Key Files
- `common/.../sync/ProfileSyncManager.kt` - Orchestrator
- `common/.../sync/SyncableProfileData.kt` - Interface
- `common/.../sync/*SyncAdapter.kt` - Adapters for each type

See: [ADDING_NOSTR_SYNC.md](../guides/ADDING_NOSTR_SYNC.md) for how to add new sync types.

---

## Payment Architecture

The Cashu wallet implementation uses:
- **cdk-kotlin** for mint operations
- **NIP-60** for cross-device backup
- **NUT-14 HTLC** for ride escrow (partially implemented)

### Current Status
- Deposits/Withdrawals: ✅ COMPLETE
- NIP-60 Sync: ✅ COMPLETE
- HTLC Create: ✅ COMPLETE
- HTLC Claim: ✅ COMPLETE (P2PK signing implemented)
- ViewModel Integration: ✅ COMPLETE
- Unit Test Infrastructure: ✅ COMPLETE (181 tests with MockK + Robolectric)
- Nip60Store Interface: ✅ COMPLETE (testable abstraction)
- Proof Conservation Tests: ✅ COMPLETE (contract tests)

### Test Infrastructure (Phase 6)

Payment code has comprehensive unit test coverage with 181 tests:

| Test File | Tests | Coverage |
|-----------|-------|----------|
| `PaymentCryptoTest.kt` | 23 | Preimage/hash generation |
| `CashuCryptoTest.kt` | 30 | hashToCurve, NUT-13, BIP-39 |
| `CashuTokenCodecTest.kt` | 30 | Token encoding/decoding |
| `HtlcResultTest.kt` | 23 | Sealed class exhaustiveness |
| `CashuBackendErrorTest.kt` | 32 | Error mapping with FakeMintApi |
| `FakeNip60StoreTest.kt` | 32 | Mock NIP-60 API behavior |
| `ProofConservationTest.kt` | 10 | Proof safety invariants |

Key infrastructure:
- `Nip60Store` interface for testable NIP-60 operations
- `FakeNip60Store` mock with call log for publish-before-delete verification
- `FakeMintApi` for queuing mock HTTP responses
- `MainDispatcherRule` for coroutine dispatcher override

Run with: `./gradlew :common:testDebugUnitTest --tests "com.ridestr.common.payment.*"`

See: [PAYMENT_ARCHITECTURE.md](PAYMENT_ARCHITECTURE.md) for full details.

---

## Documentation Index

### Protocol
- [NOSTR_EVENTS.md](../protocol/NOSTR_EVENTS.md) - All event kind definitions
- [DEPRECATION.md](../protocol/DEPRECATION.md) - Deprecated event history

### Architecture
- [STATE_MACHINES.md](./STATE_MACHINES.md) - State diagrams and transitions
- [OVERVIEW.md](./OVERVIEW.md) - This file
- [PAYMENT_ARCHITECTURE.md](./PAYMENT_ARCHITECTURE.md) - Cashu wallet and HTLC escrow

### ViewModels
- [RIDER_VIEWMODEL.md](../viewmodels/RIDER_VIEWMODEL.md) - Rider function reference
- [DRIVER_VIEWMODEL.md](../viewmodels/DRIVER_VIEWMODEL.md) - Driver function reference

### Guides
- [DEBUGGING.md](../guides/DEBUGGING.md) - Debugging principles
- [ADDING_NOSTR_SYNC.md](../guides/ADDING_NOSTR_SYNC.md) - Adding new sync features
