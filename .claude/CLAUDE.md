# Ridestr - Nostr-based Rideshare Platform

## Quick Implementation Status

See [docs/README.md](../docs/README.md) for full documentation.

| Component | Status |
|-----------|--------|
| Nostr Events | ✅ COMPLETE (8 ride + 3 backup + 2 wallet) |
| Profile Sync | ✅ COMPLETE |
| Cashu Wallet | ✅ COMPLETE (deposits/withdrawals) |
| NUT-14 HTLC | ✅ COMPLETE (P2PK signing, deferred locking) |
| Payment Integration | ✅ COMPLETE (wired, wallet pubkey handshake) |

## Project Structure
- `rider-app/` - Rider Android app (RiderViewModel.kt is main state)
- `drivestr/` - Driver Android app (DriverViewModel.kt is main state)
- `common/` - Shared code (NostrService.kt, event models, UI components)

## Build Commands
```bash
./gradlew :rider-app:assembleDebug    # Build rider app
./gradlew :drivestr:assembleDebug     # Build driver app
./gradlew build                        # Build all
```

## Common Tasks → Documentation Path

| Task | Start Here | Then Read |
|------|------------|-----------|
| Debug data issue | This file "Dual-Origin" | `.claude/skills/ridestr-protocol/SKILL.md` |
| Add new sync feature | `docs/guides/ADDING_NOSTR_SYNC.md` | `.claude/skills/add-nostr-sync.md` |
| Payment debugging | `docs/architecture/PAYMENT_ARCHITECTURE.md` | `.claude/skills/cashu-wallet.md` |
| Understand ride flow | `docs/architecture/STATE_MACHINES.md` | `.claude/skills/ridestr-protocol/SKILL.md` |
| Event structure | `docs/protocol/NOSTR_EVENTS.md` | Event source files in `common/.../events/` |

## Nostr Event Kinds (NIP-014173)

### Ride Protocol Events
| Kind | Type | Purpose |
|------|------|---------|
| 30173 | Addressable | Driver Availability Broadcast |
| 3173 | Regular | Ride Offer (rider → driver, NIP-44 encrypted) |
| 3174 | Regular | Ride Acceptance (driver → rider) |
| 3175 | Regular | Ride Confirmation (rider → driver, with PIN) |
| 30180 | Param. Replaceable | Driver Ride State (status, PIN, settlement) |
| 30181 | Param. Replaceable | Rider Ride State (location, PIN verify, preimage) |
| 3178 | Regular | Encrypted Chat |
| 3179 | Regular | Ride Cancellation |

### Profile Backup Events (NIP-44 encrypted to self)
| Kind | Type | Purpose | d-tag |
|------|------|---------|-------|
| 30174 | Param. Replaceable | Ride History | `rideshare-history` |
| 30175 | Param. Replaceable | Vehicles (driver) | `rideshare-vehicles` |
| 30176 | Param. Replaceable | Saved Locations (rider) | `rideshare-locations` |
| 7375 | Regular | Wallet Proofs (NIP-60) | - |
| 17375 | Replaceable | Wallet Metadata (NIP-60) | - |

## Critical Debugging Insight
**When debugging data issues, ALWAYS trace data at BOTH:**
1. **ORIGINATION** (where data is created/sent)
2. **RECEIVER** (where data is processed)

Example: Phantom cancellation bug was in DRIVER app (origination) not RIDER app (receiver).

## State Management Rules
- Kind 30180/30181 use `history` arrays that ACCUMULATE actions
- **CRITICAL**: Clear history when starting new ride (`clearDriverStateHistory()`, `clearRiderStateHistory()`)
- d-tag format: `ridestr-{confirmationEventId}` for subscription filtering

## Key Files

### Ride State Management
- `DriverViewModel.kt:267` - `clearDriverStateHistory()` definition
- `DriverViewModel.kt:1025` - `acceptOffer()` - MUST clear history
- `DriverViewModel.kt:2798` - `acceptBroadcastRequest()` - MUST clear history
- `RiderViewModel.kt:354` - `clearRiderStateHistory()` definition
- `NostrService.kt` - All event publishing/subscription logic

### Profile Sync Architecture
- `common/.../sync/ProfileSyncManager.kt` - Central sync orchestrator
- `common/.../sync/SyncableProfileData.kt` - Interface for all syncable data types
- `common/.../sync/ProfileSyncState.kt` - Observable sync state for UI
- `common/.../sync/Nip60WalletSyncAdapter.kt` - Wallet sync (order=0)
- `common/.../sync/RideHistorySyncAdapter.kt` - Ride history sync (order=1)
- `common/.../sync/VehicleSyncAdapter.kt` - Driver vehicles sync (order=2)
- `common/.../sync/SavedLocationSyncAdapter.kt` - Rider saved locations sync (order=3)

### Payment Architecture
- `common/.../payment/cashu/CashuBackend.kt` - Mint operations, HTLC, P2PK signing
- `common/.../payment/WalletService.kt` - Orchestration layer
- `common/.../payment/cashu/Nip60WalletSync.kt` - NIP-60 sync
- `common/.../payment/WalletKeyManager.kt` - Wallet keys with Schnorr signing
- `common/.../payment/PaymentCrypto.kt` - Preimage/hash generation
- `common/.../payment/cashu/CashuCrypto.kt` - secp256k1 crypto for Cashu

### Payment Integration (Complete - Wired)
- `RiderViewModel.kt:1823` - `lockForRide()` in `autoConfirmRide()` (AFTER acceptance)
- `WalletService.kt:324` - NUT-07 verification BEFORE HTLC swap (catches stale proofs)
- `DriverViewModel.kt:1621` - `claimHtlcPayment()` settles ride
- `WalletService.kt:263` - `lockForRide()` definition
- `WalletService.kt:365` - `claimHtlcPayment()` definition

### HTLC Implementation (Complete)
- `WalletKeyManager.kt:308` - `signSchnorr()` creates BIP-340 Schnorr signatures
- `CashuBackend.kt:630` - `signP2pkProof()` signs proofs for NUT-11/14
- `CashuBackend.kt:1044` - `verifyProofStatesBySecret()` NUT-07 verification
- Per-proof P2PK signatures in witness for HTLC claims

### Critical Payment Design: Separate Keys

**Nostr Key vs Wallet Key**: These are DIFFERENT keys for security isolation.
- **Nostr key** = User identity (signing events, NIP-44 encryption)
- **Wallet key** = P2PK escrow claims (BIP-340 Schnorr signatures)

**The Fix**: Driver sends `wallet_pubkey` in acceptance event:
- `RideAcceptanceEvent.kt` includes `wallet_pubkey` field
- Rider uses `acceptance.walletPubKey` for HTLC P2PK (not `driverPubKey`)
- Driver signs claims with wallet key → keys match → claim succeeds

### Deferred HTLC Locking

**Problem**: Original flow locked HTLC BEFORE driver accepted (wrong key).

**Solution**: Lock HTLC AFTER acceptance using driver's wallet pubkey:
```kotlin
// RiderViewModel.autoConfirmRide() - line 1823
val driverP2pkKey = acceptance.walletPubKey ?: acceptance.driverPubKey
val escrowToken = walletService?.lockForRide(fareAmount, paymentHash, driverP2pkKey, ...)
```

### Escrow Bypass (TEMPORARY - Needs Future Enforcement)
**Current behavior**: Rides proceed WITHOUT trustless payment if escrow lock fails.

**RiderViewModel.kt:1849**:
```kotlin
Log.w(TAG, "Escrow lock failed - ride will proceed without trustless payment")
```

**FUTURE FIX REQUIRED**:
- Rider app should BLOCK ride if `lockForRide()` returns null
- Show user error: "Payment setup failed. Please check your wallet balance."
- Only proceed with ride after successful escrow lock

**Root cause of failures**:
1. NIP-60 proofs not synced (auto-sync fallback added in WalletService.lockForRide)
2. Stale NIP-60 proofs (NUT-07 verification now catches these - line 324)
3. Insufficient wallet balance
4. Mint connection issues

### NUT-07 Stale Proof Verification (NEW)

**Problem**: "Token already spent" (code 11001) - NIP-60 had proofs already spent.

**Solution**: `lockForRide()` verifies proofs with mint BEFORE swap (`WalletService.kt:324-357`):
```kotlin
val stateMap = cashuBackend.verifyProofStatesBySecret(secrets)
if (stateMap != null) {
    val spentSecrets = stateMap.filterValues { it == ProofStateResult.SPENT }.keys
    if (spentSecrets.isNotEmpty()) {
        // Delete stale NIP-60 events and retry selection
        sync.deleteProofEvents(spentEventIds)
        sync.clearCache()
        selection = sync.selectProofsForSpending(amountSats, mintUrl)
    }
}
```

### Wallet Sync Architecture (NIP-60 is Source of Truth)

**Key insight**: NIP-60 IS the wallet. cdk-kotlin is only used for mint API calls (deposit/withdraw).

**`WalletService.syncWallet()`** - THE sync function that handles everything:
- Fetches ALL NIP-60 proofs (regardless of stored mint URL)
- Tries verification at current mint first
- Auto-migrates proof URLs when mint URL changed
- Cleans up spent proofs from NIP-60
- Updates displayed balance
- Called by: `connect()`, `changeMintUrl()`, `refreshBalance()`, UI "Sync Wallet" button

**Key files:**
- `WalletService.kt:382` - `syncWallet()` definition
- `WalletSettingsScreen.kt` - Settings → Wallet UI (sync, change mint, diagnostics)
- `DeveloperOptionsScreen.kt` - Only "Always Show Diagnostics" toggle for wallet

**Deprecated methods** (UI removed, kept for internal use):
- `verifyAndRefreshBalance()` - Use `syncWallet()` instead
- `resyncProofsToNip60()` - Used internally by `lockForRide()` fallback only

### Dead Code Removed (Stage 2)
- `WalletService.settleRide()` - orphaned method, deleted
- UI buttons "Verify Balance" and "Resync Proofs to NIP-60" - redundant with `syncWallet()`

### Key Payment Debugging Learnings

1. **cdk-kotlin BLOB Storage**: SQLite stores proof `C` field as BLOB (raw bytes), not hex string. Fix: Hex-encode in `findColumnValue()`.

2. **Keyset Overflow**: Some mints return amounts like `2^63` which overflows Long. Fix: `toLongOrNull()` with skip.

3. **hashToCurve Input**: HTLC secrets are JSON strings (e.g., `["HTLC",{...}]`), not hex. Hash directly as UTF-8.

4. **NIP-60 Auto-Sync**: If NIP-60 proofs insufficient but cdk-kotlin has enough, auto-sync before retrying.

5. **hashToCurve Algorithm (NUT-00)**: Uses **double SHA256**, not single:
   - `msg_hash = SHA256(DOMAIN_SEPARATOR || message)`
   - `final = SHA256(msg_hash || counter)` ← counter is 4-byte little-endian
   - Wrong Y values cause NUT-07 verification to check non-existent proofs

6. **HTLC Refund Gap**: Rider locks HTLC with locktime + refund pubkey, BUT `EscrowLock` is not persisted and no `refundExpiredHtlc()` exists. If driver never claims, rider cannot reclaim funds after expiry.

7. **Duplicate Confirmation Race Condition** (January 2026): When acceptance arrives, `autoConfirmRide()` launches async. If user taps manual confirm button during async window, TWO Kind 3175 events sent → driver stores first, rider stores second → different `confirmationEventId` → all subsequent events filtered as "different ride". **Fix**: Set `isConfirmingRide = true` BEFORE coroutine launch + add guards in `confirmRide()`.

## Profile Sync Architecture

### Overview
ProfileSyncManager coordinates all profile data sync with Nostr relays:
- Ordered sync on key import (wallet → history → vehicles/locations)
- Shared KeyManager instance (avoids multiple KeyManager race conditions)
- Observable sync state for UI feedback

### Sync Order (on key import)
1. **Wallet (order=0)** - Highest priority, needed for payments
2. **Ride History (order=1)** - May reference payments
3. **Vehicles (order=2, driver)** - Profile data
4. **Saved Locations (order=3, rider)** - Convenience data

### Adding New Sync Features
To add a new Nostr-synced data type:
1. Choose unused event kind (30177+)
2. Create `XxxBackupEvent.kt` in `common/.../nostr/events/`
3. Create `XxxSyncAdapter.kt` implementing `SyncableProfileData`
4. Add backup/fetch methods to `NostrService.kt`
5. Register adapter in MainActivity:
   ```kotlin
   profileSyncManager.registerSyncable(XxxSyncAdapter(repository, nostrService))
   ```
6. Test: Fresh install → import key → verify restore
