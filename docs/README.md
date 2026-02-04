# Ridestr Documentation

**Last Updated**: 2026-02-03

Ridestr is a decentralized rideshare platform built on Nostr. This documentation provides comprehensive coverage of the protocol, architecture, and implementation.

---

## Implementation Status

| Component | Status | Documentation |
|-----------|--------|---------------|
| Nostr Events (NIP-014173) | ✅ COMPLETE | [NOSTR_EVENTS.md](protocol/NOSTR_EVENTS.md) |
| State Machines | ✅ COMPLETE | [STATE_MACHINES.md](architecture/STATE_MACHINES.md) |
| Profile Sync | ✅ COMPLETE | [OVERVIEW.md](architecture/OVERVIEW.md) |
| Cashu Wallet (deposit/withdraw) | ✅ COMPLETE | [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) |
| NIP-60 Backup/Restore | ✅ COMPLETE | [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) |
| NUT-14 HTLC | ✅ COMPLETE | P2PK signing, wallet pubkey handshake, deferred locking |
| NUT-13 Deterministic Secrets | ✅ COMPLETE | Seed-based recovery |
| NUT-09 Restore | ✅ COMPLETE | Mint scanning for recovery |
| Payment Integration | ✅ COMPLETE | Fully wired, HTLC escrow, NIP-60 sync |
| RoadFlare Network | ✅ COMPLETE | Personal driver network, three-state mode, location sharing |
| Security Hardening | ✅ COMPLETE | Backup exclusions, pubkey validation, WebSocket concurrency, signature verification |
| Payment Test Harness | ✅ COMPLETE | 181 unit tests, FakeMintApi, FakeNip60Store, proof conservation contracts |
| Nip60Store Interface | ✅ COMPLETE | Testable abstraction for NIP-60 operations |

---

## Documentation Index

### Protocol
- [NOSTR_EVENTS.md](protocol/NOSTR_EVENTS.md) - All 18 active Nostr event kind definitions
- [DEPRECATION.md](protocol/DEPRECATION.md) - Deprecated event history and migration notes

### Architecture
- [OVERVIEW.md](architecture/OVERVIEW.md) - System design, data flow, profile sync
- [STATE_MACHINES.md](architecture/STATE_MACHINES.md) - Rider and driver state diagrams
- [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) - Cashu wallet, HTLC escrow
- [CONNECTIONS.md](CONNECTIONS.md) - Cross-module dependency map with Mermaid diagrams

### ViewModels
- [RIDER_VIEWMODEL.md](viewmodels/RIDER_VIEWMODEL.md) - ~40 public functions, state fields
- [DRIVER_VIEWMODEL.md](viewmodels/DRIVER_VIEWMODEL.md) - ~35 public functions, state fields

### Guides
- [DEBUGGING.md](guides/DEBUGGING.md) - Debugging principles and common patterns
- [ADDING_NOSTR_SYNC.md](guides/ADDING_NOSTR_SYNC.md) - How to add new Nostr-synced data types

---

## Quick Links for Agents

| Task | File | Key Section |
|------|------|-------------|
| Debug data issues | [DEBUGGING.md](guides/DEBUGGING.md) | Dual-Origin Principle |
| Add new sync feature | [ADDING_NOSTR_SYNC.md](guides/ADDING_NOSTR_SYNC.md) | Step-by-step checklist |
| Understand ride flow | [STATE_MACHINES.md](architecture/STATE_MACHINES.md) | State diagrams |
| Payment escrow flow | [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) | HTLC Escrow Flow |
| Event structures | [NOSTR_EVENTS.md](protocol/NOSTR_EVENTS.md) | Event Definitions |

---

## Payment Integration - COMPLETE

### HTLC Implementation (Done)

**What's implemented:**
- `WalletKeypair.signSchnorr()` - BIP-340 Schnorr signatures
- `CashuBackend.signP2pkProof()` - Per-proof witness signatures
- `claimHtlcToken()` - Proper P2PK signatures in witness
- Wallet pubkey handshake via acceptance event

### Key Design: Wallet Pubkey Handshake

**Problem solved:** Driver's Nostr key ≠ wallet key. HTLC P2PK must use wallet key.

**Solution:**
1. Driver includes `wallet_pubkey` in acceptance event (Kind 3174)
2. Rider receives acceptance → locks HTLC with `acceptance.walletPubKey`
3. Driver claims with wallet key signature → keys match → success

### Deferred HTLC Locking

HTLC is locked **AFTER** acceptance (not before):
- `RiderViewModel.autoConfirmRide()` locks HTLC using driver's wallet pubkey
- This ensures the P2PK condition matches the key the driver will sign with

### NUT-07 Stale Proof Verification (NEW)

**Problem solved:** "Token already spent" (code 11001) - NIP-60 had stale proofs.

**Solution:**
1. `lockForRide()` verifies proofs with mint (NUT-07) BEFORE swap
2. If any proofs are SPENT → delete their NIP-60 events → retry selection
3. Only proceed with verified UNSPENT proofs

**Location:** `WalletService.kt:324-357`

See [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) for detailed flow diagrams.

---

## What IS Connected (Complete Integration)

### HTLC Payment Flow (Complete)
- `RiderViewModel:2839` → `walletService.lockForRide()` in `autoConfirmRide()` - Creates HTLC escrow **AFTER** acceptance
- `WalletService.kt:1085` → NUT-07 verification BEFORE swap (catches stale proofs)
- `DriverViewModel:2189` → `walletService.claimHtlcPayment()` - Settles ride with P2PK signature
- `WalletKeyManager.signSchnorr()` - BIP-340 Schnorr signatures for P2PK
- `CashuBackend.signP2pkProof()` - Per-proof witness signatures

### Wallet Pubkey Handshake (Critical)
- `RideAcceptanceEvent` includes `wallet_pubkey` field
- Driver sends wallet pubkey in acceptance (Kind 3174)
- Rider uses `acceptance.walletPubKey` for HTLC P2PK condition
- Driver signs claims with wallet key → keys match → claim succeeds

### Preimage Sharing (Complete)
- `RiderViewModel.sharePreimageWithDriver()` - Shares preimage via Kind 30181
- `RiderRideStateEvent.PREIMAGE_SHARE` action type exists

## Wallet UI

### Deposit/Withdraw
- ✅ **Deposit works**: `WalletDetailScreen.kt` - full flow with QR code
- ✅ **Withdraw works**: `WalletDetailScreen.kt` - full flow with fee preview
- Navigation: Tap wallet card → WalletDetailScreen → Deposit/Withdraw buttons

---

## Project Structure

```
ridestr/
├── rider-app/          # Rider Android app
│   └── viewmodels/RiderViewModel.kt
├── drivestr/           # Driver Android app
│   └── viewmodels/DriverViewModel.kt
├── common/             # Shared code
│   ├── nostr/          # Nostr events, service, relay management
│   ├── payment/        # Cashu wallet, HTLC, NIP-60
│   ├── sync/           # ProfileSyncManager, adapters
│   └── data/           # Repositories
└── docs/               # This documentation
```

---

## Key Files Reference

| Component | File | Line Reference |
|-----------|------|----------------|
| Nostr Service | `common/.../nostr/NostrService.kt` | Event publishing/subscription |
| Event Kinds | `common/.../nostr/events/RideshareEventKinds.kt` | All kind constants |
| Cashu Backend | `common/.../payment/cashu/CashuBackend.kt` | Mint operations |
| Wallet Service | `common/.../payment/WalletService.kt` | Orchestration layer |
| Profile Sync | `common/.../sync/ProfileSyncManager.kt` | Sync orchestrator |
| Rider State | `rider-app/.../viewmodels/RiderViewModel.kt` | ~2800 lines |
| Driver State | `drivestr/.../viewmodels/DriverViewModel.kt` | ~2800 lines |

---

## Security Hardening (January 2026)

### Summary

| Component | Commit | Description |
|-----------|--------|-------------|
| Backup Exclusions | 420f54b | Fixed SharedPrefs filenames + privacy data exclusions |
| Pubkey Validation | 420f54b | expectedDriverPubKey/expectedRiderPubKey wired in subscriptions |
| WebSocket Concurrency | 0e658f6 | Bounded channel (256), connection generation tracking |
| Signature Verification | d7c9064 | Relay-level event.verify() (NIP-01 Schnorr) |

### Backup Exclusions

**Files:** `*/res/xml/backup_rules.xml` and `*/res/xml/data_extraction_rules.xml` (both apps)

**Excluded:**
- **Secrets:** `ridestr_secure_keys.xml`, `ridestr_wallet_keys.xml`, `ridestr_wallet_storage.xml`, `ridestr_settings.xml`
- **Privacy Data:** `ridestr_ride_history.xml`, `ridestr_saved_locations.xml`, `ridestr_vehicles.xml`, `roadflare_*.xml`

**Rationale:** Nostr sync (Kind 30174, 30177) is the recovery path. Cloud backup exclusion prevents key exposure.

### Pubkey Validation Wiring

**NostrService.kt signatures:**
```kotlin
subscribeToAcceptance(offerEventId, expectedDriverPubKey, onAcceptance)
subscribeToConfirmation(acceptanceEventId, scope, expectedRiderPubKey, onConfirmation)
```

**Call sites:**
- `RiderViewModel`: Lines 1190, 1325, 1439, 1734, 1764, 2022 - pass `driverPubKey`
- `DriverViewModel`: Lines 652, 1361, 2274, 3694 - pass `riderPubKey`
- State subscriptions (NostrService.kt): Lines 722, 794 - pass expected pubkey

### WebSocket Concurrency

**RelayConnection.kt pattern:**
```kotlin
private val messageChannel = Channel<Pair<Long, String>>(capacity = 256)
private var connectionGeneration = 0L  // Increments on connect()
```

**Prevents:** Memory exhaustion, stale callback corruption, race conditions, post-disconnect reconnects.

### Signature Verification Flow

```
Relay message → RelayConnection.handleMessage()
    → event.verify() (NIP-01) → reject if invalid
    → onEvent → NostrService
    → Parser with expectedPubKey → reject if mismatch
    → ViewModel receives validated data
```

**Event parsers with expectedPubKey:**
- `RideAcceptanceEvent.parse(event, expectedDriverPubKey)`
- `RideConfirmationEvent.parseEncrypted(event, expectedRiderPubKey)`
- `DriverRideStateEvent.parse(event, expectedDriverPubKey)`
- `RiderRideStateEvent.parse(event, expectedRiderPubKey)`
