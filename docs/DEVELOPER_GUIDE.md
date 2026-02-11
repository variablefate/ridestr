# Developer & Agent Guide

Technical reference for developers and AI agents working on the Ridestr codebase.

---

## Quick Navigation

| Task | Start Here |
|------|------------|
| Debug data issues | [DEBUGGING.md](guides/DEBUGGING.md) - Dual-Origin Principle |
| Add new sync feature | [ADDING_NOSTR_SYNC.md](guides/ADDING_NOSTR_SYNC.md) |
| Understand ride flow | [STATE_MACHINES.md](architecture/STATE_MACHINES.md) |
| Payment escrow flow | [PAYMENT_ARCHITECTURE.md](architecture/PAYMENT_ARCHITECTURE.md) |
| Modify payment code | [PAYMENT_SAFETY.md](architecture/PAYMENT_SAFETY.md) - **Read first** |
| Event structures | [NOSTR_EVENTS.md](protocol/NOSTR_EVENTS.md) |

---

## Key Files Reference

| Component | File | Purpose |
|-----------|------|---------|
| Nostr Service | `common/.../nostr/NostrService.kt` | Event publishing/subscription facade |
| Rideshare Domain | `common/.../nostr/RideshareDomainService.kt` | Ride protocol (offers, state, chat, cancel) |
| Event Kinds | `common/.../nostr/events/RideshareEventKinds.kt` | All kind constants |
| Cashu Backend | `common/.../payment/cashu/CashuBackend.kt` | Mint operations (~2000 lines) |
| Wallet Service | `common/.../payment/WalletService.kt` | Payment orchestration (~1500 lines) |
| Profile Sync | `common/.../sync/ProfileSyncManager.kt` | Sync orchestrator |
| Rider State | `rider-app/.../viewmodels/RiderViewModel.kt` | Rider state machine |
| Driver State | `drivestr/.../viewmodels/DriverViewModel.kt` | Driver state machine |

---

## Payment System

### HTLC Flow (Complete)

1. Rider generates preimage + paymentHash
2. Rider sends offer (Kind 3173) - paymentHash NOT included yet
3. Driver accepts (Kind 3174) - includes `wallet_pubkey`
4. Rider locks HTLC using driver's wallet pubkey (deferred locking)
5. Rider confirms (Kind 3175) - includes paymentHash + escrowToken
6. At pickup: PIN verification → preimage shared via Kind 30181
7. Driver claims with P2PK signature using wallet key

### Key Design: Separate Keys

- **Nostr key** = Identity (event signing, NIP-44 encryption)
- **Wallet key** = Payment (P2PK escrow claims, BIP-340 Schnorr)

Driver's `wallet_pubkey` in Kind 3174 ensures HTLC is locked to correct key.

### Stale Proof Handling

`lockForRide()` verifies proofs with mint (NUT-07) BEFORE swap:
- If SPENT proofs found → delete NIP-60 events → retry selection
- Prevents "Token already spent" (code 11001) errors

---

## Test Infrastructure

**181 unit tests** in `common/src/test/.../payment/`

| File | Tests | Coverage |
|------|-------|----------|
| `PaymentCryptoTest.kt` | 23 | Preimage/hash generation |
| `CashuCryptoTest.kt` | 30 | hashToCurve, NUT-13, BIP-39 |
| `CashuTokenCodecTest.kt` | 30 | Token encoding/decoding |
| `HtlcResultTest.kt` | 23 | Sealed class exhaustiveness |
| `CashuBackendErrorTest.kt` | 32 | Error mapping with FakeMintApi |
| `FakeNip60StoreTest.kt` | 32 | Mock NIP-60 API behavior |
| `ProofConservationTest.kt` | 10 | Proof safety invariants |

**Run tests:**
```bash
./gradlew :common:testDebugUnitTest --tests "com.ridestr.common.payment.*"
# Or: run_tests.bat
```

**Test doubles:**
- `FakeMintApi` - Queue mock HTTP responses
- `FakeNip60Store` - Mock NIP-60 with call log verification
- `CashuBackend.setTestState()` - Bypass HTTP for unit tests

---

## Security Implementation

### Backup Exclusions

Files excluded from Android cloud backup (`backup_rules.xml`):
- Secrets: `ridestr_secure_keys.xml`, `ridestr_wallet_keys.xml`, `ridestr_wallet_storage.xml`
- Privacy: `ridestr_ride_history.xml`, `ridestr_saved_locations.xml`, `roadflare_*.xml`

Recovery path: Nostr sync (Kind 30174, 30177)

### Pubkey Validation

All subscription methods validate expected pubkey:
```kotlin
subscribeToAcceptance(offerEventId, expectedDriverPubKey, onAcceptance)
subscribeToConfirmation(acceptanceEventId, scope, expectedRiderPubKey, onConfirmation)
```

### Signature Verification Flow

```
Relay → event.verify() (NIP-01) → Parser with expectedPubKey → ViewModel
```

### WebSocket Concurrency

`RelayConnection.kt` uses bounded channel (256) + connection generation tracking to prevent memory exhaustion and stale callbacks.

---

## Common Patterns

### Dual-Origin Debugging

When debugging data issues, trace at BOTH:
1. **Origination** - Where data is created/sent
2. **Receiver** - Where data is processed

Example: Phantom cancellation bug was in DRIVER app (origination) not RIDER app (receiver).

### History Accumulation

Kind 30180/30181 use `history` arrays that ACCUMULATE actions.
**Critical:** Clear history when starting new ride (`clearDriverStateHistory()`, `clearRiderStateHistory()`).

### EOSE-Aware Queries

All Nostr queries use `onEose` callback for early exit instead of fixed timeouts:
```kotlin
relayManager.subscribe(filter, onEvent = {...}, onEose = { deferred.complete(Unit) })
withTimeoutOrNull(8000) { deferred.await() }
```

---

## Module READMEs

- [common/src/main/README.md](../common/src/main/README.md) - Shared module details
- [rider-app/src/main/README.md](../rider-app/src/main/README.md) - Rider app specifics
- [drivestr/src/main/README.md](../drivestr/src/main/README.md) - Driver app specifics
