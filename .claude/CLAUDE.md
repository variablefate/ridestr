# Ridestr - Nostr-based Rideshare Platform

## Quick Implementation Status

See [docs/README.md](../docs/README.md) for full documentation.

| Component | Status |
|-----------|--------|
| Nostr Events | ✅ COMPLETE (8 ride + 2 backup + 2 wallet + 1 admin) |
| Profile Sync | ✅ COMPLETE |
| Cashu Wallet | ✅ COMPLETE (deposits/withdrawals) |
| NUT-14 HTLC | ✅ COMPLETE (P2PK signing, deferred locking) |
| NUT-13 Deterministic Secrets | ✅ COMPLETE (seed-based recovery) |
| NUT-09 Restore | ✅ COMPLETE (mint scanning for recovery) |
| NUT-17 WebSocket | ✅ COMPLETE (real-time state updates with polling fallback) |
| Payment Integration | ✅ COMPLETE (wired, wallet pubkey handshake) |
| Multi-Mint Protocol | 🚧 Phase 1 COMPLETE (payment fields in events) |
| State Machine | 🚧 Phase 3 COMPLETE (AtoB pattern, driver broadcasts state) |
| Relay Optimizations | ✅ COMPLETE (EOSE-aware queries, progressive backoff) |
| Wallet Auto-Refresh | ✅ COMPLETE (balance sync after all major payments) |
| Remote Config | ✅ COMPLETE (Kind 30182, fare rates from admin pubkey) |
| NIP-60 Cross-App Safety | ✅ COMPLETE (cross-mint proof preservation, metadata protection) |
| Security Hardening | ✅ COMPLETE (backup exclusions, pubkey validation, WebSocket concurrency, signature verification, encryption fallback warning) |
| RoadFlare Status Detection | ✅ COMPLETE (auto-sync, out-of-order rejection, staleness filter, key refresh) |

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
| 30173 | Addressable | Driver Availability Broadcast + mint_url/payment_methods |
| 3173 | Regular | Ride Offer (rider → driver) + mint_url/payment_method |
| 3174 | Regular | Ride Acceptance (driver → rider) + mint_url/payment_method |
| 3175 | Regular | Ride Confirmation (rider → driver, with paymentHash + escrowToken) |
| 30180 | Param. Replaceable | Driver Ride State (status, PIN, settlement) |
| 30181 | Param. Replaceable | Rider Ride State (location, PIN verify, preimage) |
| 3178 | Regular | Encrypted Chat |
| 3179 | Regular | Ride Cancellation |

### Profile Backup Events (NIP-44 encrypted to self)
| Kind | Type | Purpose | d-tag |
|------|------|---------|-------|
| 30174 | Param. Replaceable | Ride History | `rideshare-history` |
| 30177 | Param. Replaceable | **Unified Profile** (vehicles, locations, settings) | `rideshare-profile` |
| 7375 | Regular | Wallet Proofs (NIP-60) | - |
| 17375 | Replaceable | Wallet Metadata (NIP-60) | - |

### Admin Configuration Events
| Kind | Type | Purpose | d-tag |
|------|------|---------|-------|
| 30182 | Param. Replaceable | Platform config (fare rates, mints, versions) | `ridestr-admin-config` |

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

### State Machine Architecture (Phase 1 - Validation Only)

Formal state machine implementation following the AtoB pattern.

**Key Files:**
- `common/.../state/RideState.kt` - Unified state enum (CREATED → ACCEPTED → CONFIRMED → EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED | CANCELLED)
- `common/.../state/RideEvent.kt` - Event types (Accept, Confirm, StartRoute, Arrive, VerifyPin, StartRide, Complete, Cancel, etc.)
- `common/.../state/RideContext.kt` - Rich context for guard/action evaluation (participant identity, payment data, PIN state)
- `common/.../state/RideTransition.kt` - Transition table with 16 defined transitions
- `common/.../state/RideGuards.kt` - Named guards: `isRider`, `isDriver`, `isPinVerified`, `hasEscrowLocked`, `canSettle`
- `common/.../state/RideActions.kt` - Named actions: `assignDriver`, `lockEscrow`, `startRideAfterPin`, `settlePayment`
- `common/.../state/RideStateMachine.kt` - Processor with `processEvent()`, `canTransition()`, `availableEvents()`

**ViewModel Integration:**
- `DriverViewModel.kt:~193` - `stateMachine`, `rideContext`, `validateTransition()`, `currentRideState()`
- `RiderViewModel.kt:~180` - Same pattern
- Both ViewModels call `validateTransition()` before state changes - logs warnings but doesn't block (Phase 1)

### Pre-Confirmation Driver Monitoring (Issue #22)
When rider sends a direct offer to a specific driver, the rider app monitors that driver's availability:
- `NostrService.subscribeToDriverAvailability()` - Subscribe to specific driver's Kind 30173
- `RiderViewModel.subscribeToSelectedDriverAvailability()` - Starts monitoring on `sendRideOffer()`
- `RiderViewModel.closeDriverAvailabilitySubscription()` - Closes on acceptance or cancel
- If driver goes offline → shows "Driver Unavailable" dialog → auto-cancels → returns to IDLE

### Profile Sync Architecture
- `common/.../sync/ProfileSyncManager.kt` - Central sync orchestrator
- `common/.../sync/SyncableProfileData.kt` - Interface for all syncable data types
- `common/.../sync/ProfileSyncState.kt` - Observable sync state for UI
- `common/.../sync/Nip60WalletSyncAdapter.kt` - Wallet sync (order=0)
- `common/.../sync/ProfileSyncAdapter.kt` - **Unified profile sync (order=1)** - vehicles, locations, settings
- `common/.../sync/RideHistorySyncAdapter.kt` - Ride history sync (order=2)

### Payment Architecture
- `common/.../payment/cashu/CashuBackend.kt` - Mint operations, HTLC, P2PK signing, NUT-09 restore
- `common/.../payment/WalletService.kt` - Orchestration layer, `recoverFromSeed()` for NUT-13 recovery
- `common/.../payment/cashu/Nip60WalletSync.kt` - NIP-60 sync (includes counter backup)
- `common/.../payment/WalletKeyManager.kt` - Wallet keys with Schnorr signing
- `common/.../payment/WalletStorage.kt` - Local persistence + NUT-13 counters
- `common/.../payment/PaymentCrypto.kt` - Preimage/hash generation
- `common/.../payment/cashu/CashuCrypto.kt` - secp256k1 crypto, NUT-13 deterministic derivation

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

### NUT-13 Deterministic Secrets (Complete)

All proof secrets are derived deterministically from the BIP-39 mnemonic for seed-based recovery.

**Key Files:**
- `CashuCrypto.kt:mnemonicToSeed()` - BIP-39 seed derivation (PBKDF2-SHA512)
- `CashuCrypto.kt:deriveSecrets()` - NUT-13 HMAC-SHA256 derivation
- `WalletStorage.kt:incrementCounter()` - Per-keyset counter management
- `Nip60WalletSync.kt:publishWalletMetadata()` - Counter backup to Kind 17375

**Derivation Formula:**
```
domain = "Cashu_KDF_HMAC_SHA256"
secret = HMAC-SHA256(seed, domain || keyset_id || counter || 0x00)
r = HMAC-SHA256(seed, domain || keyset_id || counter || 0x01) mod N
```

**Recovery:** `WalletService.recoverFromSeed()` scans mint via NUT-09 `/v1/restore`

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
// RiderViewModel.autoConfirmRide() - line 2910
val driverP2pkKey = acceptance.walletPubKey ?: acceptance.driverPubKey
val escrowToken = walletService?.lockForRide(fareAmount, paymentHash, driverP2pkKey, ...)
```

### PaymentHash Migration (January 2026)

**Problem**: paymentHash was sent in offer (Kind 3173), but HTLC wasn't locked until after acceptance. Boost offers (resending with higher fare) overwrote driver's stored paymentHash with null → HTLC claim failed.

**Solution**: Move paymentHash from offer to confirmation (Kind 3175):
```
OLD FLOW (BUG):
1. Rider sends OFFER with paymentHash → Driver stores it
2. Driver accepts → Rider locks HTLC
3. Rider sends CONFIRMATION (no payment data)
4. BOOST: New offer WITHOUT paymentHash → overwrites driver's hash → BUG!

NEW FLOW (FIXED):
1. Rider sends OFFER WITHOUT paymentHash (just fare/route)
2. Driver accepts, sends walletPubKey
3. Rider locks HTLC, sends CONFIRMATION WITH paymentHash + escrowToken
4. Driver extracts paymentHash from confirmation
5. BOOST: Only updates fare, paymentHash already in state → SAFE
```

**Key Files Changed**:
- `RideConfirmationEvent.kt` - Added `paymentHash` and `escrowToken` to create/decrypt
- `RideOfferEvent.kt` - Removed `paymentHash` from create() (kept in decrypt for backward compat)
- `NostrService.kt` - `sendRideOffer()` no longer takes paymentHash, `confirmRide()` now takes both
- `RiderViewModel.kt:2940` - `confirmRide()` passes paymentHash + escrowToken
- `DriverViewModel.kt:2302` - Extracts paymentHash from `confirmation.paymentHash`
- `DriverViewModel.kt:1331` - `activePaymentHash = null` in acceptOffer() (comes from confirmation now)

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

### NIP-60 Cross-App Wallet Safety (January 2026)

**Problem**: Users importing Nostr keys with existing NIP-60 wallet data from other apps (Minibits, nutstash, etc.) could accidentally lose funds.

**Risks Fixed**:
1. **"Start Fresh" had no warning** - Users could overwrite existing wallet backup without realizing
2. **Cross-mint proof deletion** - `syncNip60WithMint()` deleted ALL proofs when balance mismatch, even from other mints
3. **Metadata overwrite** - `publishWalletMetadata()` silently replaced other app's backup

**Solutions**:
- `WalletSetupScreen.kt` - Confirmation dialog when choosing "Start Fresh" with existing wallet
- `WalletService.kt:2667-2710` - Only delete proofs from CURRENT mint that are verified SPENT
- `Nip60WalletSync.kt:520-625` - `getExistingWalletMetadata()` checks before overwrite, `forceOverwrite` parameter

**Key Files**:
- `WalletSetupScreen.kt` - `showStartFreshConfirmation`, `showRestoreFailedDialog` states
- `Nip60WalletSync.kt` - `ExistingWalletInfo` data class, `getExistingWalletMetadata()` method

### NUT-07 Stale Proof Verification

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

6. **HTLC Refund Preimage Storage** (January 2026): `PendingHtlc` now stores the preimage for future-proof refunds. Some mints don't verify the hash (allowing zeros workaround), but if they fix this, refunds need the real preimage. Preimage threaded through `lockForRide()` → `PendingHtlc` → `refundExpiredHtlc()`. Old HTLCs without preimage fall back to zeros.

7. **Duplicate Confirmation Race Condition** (January 2026): When acceptance arrives, `autoConfirmRide()` launches async. If user taps manual confirm button during async window, TWO Kind 3175 events sent → driver stores first, rider stores second → different `confirmationEventId` → all subsequent events filtered as "different ride". **Fix**: Set `isConfirmingRide = true` BEFORE coroutine launch + add guards in `confirmRide()`.

8. **Unblinding Key Lookup (CDK Pattern)**: When unblinding signatures from mint, use `responseAmount` from the mint's BlindSignature response to look up the public key, NOT `pms.amount` from our PreMintSecret. The mint returns `{amount, id, C_}` for each signature - use that `amount` for `keyset.keys[responseAmount]`. If mint reorders responses or assigns different amounts, using our premint amount would select wrong key → invalid unblinded proof.

## Relay & Mint Performance Optimizations

### EOSE-Aware Relay Queries
**Problem**: NIP-60 queries waited fixed timeout (8s) even when relay responded in 500ms.

**Solution**: `RelayManager.subscribe()` now supports `onEose` callback. Use `CompletableDeferred` + `withTimeoutOrNull` to exit immediately when EOSE received.

**Files updated:**
- `RelayManager.kt` - Added `onEose` parameter to `subscribe()`
- `NostrService.kt` - All 16 fetch/count/delete methods use EOSE-aware waiting (no `runBlocking` in callbacks)
- `RemoteConfigManager.kt` - `fetchConfig()` uses EOSE-aware waiting
- `Nip60WalletSync.kt` - `fetchProofs()`, `hasExistingWallet()`, `restoreFromNostr()` use EOSE-aware waiting

### Progressive Backoff for Mint Polling
**Problem**: Fixed 5s delay when polling mint for Lightning payment status.

**Solution**: Gradual ramp `500ms → 1s → 2s → 3s → 4s → 5s...` catches fast payments quickly.

**File:** `CashuBackend.kt:2834` - Melt quote polling uses `pollDelays` list

**Impact:** Cross-mint bridge payments complete in ~1-3s instead of 5-10s for fast Lightning.

### Automatic Wallet Refresh (January 2026)
All major payment operations now trigger automatic NIP-60 refresh + diagnostics update:

| Operation | Location | What Happens |
|-----------|----------|--------------|
| HTLC Claim (driver) | `WalletService.claimHtlcPayment()` | Publishes proofs → fetches fresh → `updateDiagnostics()` |
| Ride Completion (rider) | `RiderViewModel.handleRideCompletion()` | `markHtlcClaimedByPaymentHash()` → `refreshBalance()` |
| Ride Cancellation (rider) | `RiderViewModel.handleDriverCancellation()` | `refreshBalance()` (checks for expired HTLC refunds) |
| Withdrawal | `WalletService.executeWithdraw()` | NIP-60 fetch via `meltWithProofs()` |
| Cross-Mint Bridge | `WalletService.bridgePayment()` | NIP-60 fetch after deposit claim |

**Result:** Diagnostics icon shows green (synced) after all major payment events.

## Security Hardening (January 2026)

### Summary Table

| Component | Commit | Status |
|-----------|--------|--------|
| Backup Exclusions | 420f54b | ✅ Fixed - correct SharedPrefs filenames + privacy data |
| Pubkey Validation | 420f54b | ✅ Wired - expectedDriverPubKey/expectedRiderPubKey |
| WebSocket Concurrency | 0e658f6 | ✅ Hardened - bounded channel, generation tracking |
| Signature Verification | d7c9064 | ✅ Implemented - relay-level event.verify() |
| Encryption Fallback Warning | 6647b88 | ✅ Implemented - warns user when storage falls back to plaintext |

### Backup Exclusions

**Files:** `*/res/xml/backup_rules.xml` and `*/res/xml/data_extraction_rules.xml` (both apps)

**Excluded SharedPreferences:**
- **Secrets:** `ridestr_secure_keys.xml`, `ridestr_wallet_keys.xml`, `ridestr_wallet_storage.xml`, `ridestr_settings.xml`
- **Privacy Data:** `ridestr_ride_history.xml`, `ridestr_saved_locations.xml`, `ridestr_vehicles.xml`, `roadflare_*.xml`

**Rationale:** Nostr sync (Kind 30174, 30177) is the recovery path. Cloud backup exclusion prevents accidental key exposure.

### Pubkey Validation Wiring

**NostrService.kt method signatures updated:**
```kotlin
subscribeToAcceptance(offerEventId, expectedDriverPubKey, onAcceptance)  // Direct offers
subscribeToConfirmation(acceptanceEventId, scope, expectedRiderPubKey, onConfirmation)  // Confirmations
```

**Call sites updated:**
- `RiderViewModel`: Lines 1190, 1325, 1439, 1734, 1764, 2022 - pass `driverPubKey`
- `DriverViewModel`: Lines 652, 1361, 2274, 3694 - pass `riderPubKey`
- State subscriptions: Lines 722, 794 - pass expected pubkey to parsers

### WebSocket Concurrency (RelayConnection.kt)

**Pattern:** Bounded message channel (capacity=256) with connection generation tracking

```kotlin
private val messageChannel = Channel<Pair<Long, String>>(capacity = 256)
private var connectionGeneration = 0L  // Increments on connect()
```

**Prevents:**
- Memory exhaustion under bursty traffic (bounded queue)
- Stale callback corruption after reconnect (generation check)
- Race conditions in state/socket (synchronized blocks)
- Post-disconnect reconnects (shouldReconnect flag)

### Signature Verification Flow

```
Relay message → RelayConnection.handleMessage()
    → event.verify() (NIP-01 Schnorr) → reject if invalid
    → onEvent callback → NostrService
    → Parser with expectedPubKey validation → reject if mismatch
    → ViewModel receives validated data
```

**Event parsers with expectedPubKey:**
- `RideAcceptanceEvent.parse(event, expectedDriverPubKey)`
- `RideConfirmationEvent.parseEncrypted(event, expectedRiderPubKey)`
- `DriverRideStateEvent.parse(event, expectedDriverPubKey)`
- `RiderRideStateEvent.parse(event, expectedRiderPubKey)`

### Key Files Reference

| File | Security Feature |
|------|-----------------|
| `RelayConnection.kt:45` | Bounded message channel definition |
| `RelayConnection.kt:78` | Connection generation tracking |
| `RelayConnection.kt:156` | `event.verify()` before processing |
| `NostrService.kt:722` | Driver state expectedPubKey |
| `NostrService.kt:794` | Rider state expectedPubKey |
| `NostrService.kt:1203` | Acceptance expectedDriverPubKey |
| `NostrService.kt:1358` | Confirmation expectedRiderPubKey |
| `RideAcceptanceEvent.kt:25` | Pubkey validation in parse() |
| `RideConfirmationEvent.kt:30` | Pubkey validation in parseEncrypted() |

### Encryption Fallback Warning

**Problem:** Three storage classes silently fall back to unencrypted SharedPreferences when `EncryptedSharedPreferences` fails (emulators, rooted devices, keystore corruption).

**Storage classes affected:**
- `SecureKeyStorage.kt` - Nostr private key
- `WalletKeyManager.kt` - Wallet mnemonic
- `WalletStorage.kt` - Wallet proofs, counters

**Solution:** Each storage class tracks fallback status via `isUsingFallback()`. Manager classes expose this:
- `KeyManager.isUsingUnencryptedStorage()`
- `WalletService.isUsingUnencryptedStorage()`

**Warning dialog:** `EncryptionFallbackWarningDialog.kt` - Non-cancelable dialog shown on MainScreen startup (release builds only). Dismissal persisted via `SettingsManager.setEncryptionFallbackWarned()`.

**Key files:**
- `SecureKeyStorage.kt:32,50,55-60` - Flag + getter
- `WalletKeyManager.kt:37,52,56-61` - Flag + getter
- `WalletStorage.kt:44,59,63-68` - Flag + getter
- `SettingsManager.kt:85,880-896` - Dismissal prefs
- `rider-app/MainActivity.kt:927-939,1127-1134` - Warning check + dialog
- `drivestr/MainActivity.kt:940-950,1266-1273` - Warning check + dialog

## RoadFlare Status Detection (January 2026)

### Problem Summary
RoadFlare screens showed drivers as offline even when online due to:
1. **Cross-device sync gap**: RoadFlare state only synced during onboarding, not on resume/go-online
2. **Out-of-order 30014 events**: No timestamp checks → late OFFLINE could override newer ONLINE
3. **Selection sheet staleness**: Treated any location as "online" forever
4. **Stale keys**: Rider may have outdated RoadFlare key

### Fixes Implemented

| Fix | Files | Description |
|-----|-------|-------------|
| Auto-sync on go-online | `DriverViewModel.kt:3801` | `ensureRoadflareStateSynced()` fetches state from Nostr if local is empty |
| Out-of-order rejection | `RoadflareTab.kt:150,208-235` | Track `lastLocationCreatedAt`, reject events with older timestamps |
| Staleness filter | `RiderModeScreen.kt:3491-3560,3732-3745` | `RoadflareDriverState` wrapper, 10-min freshness check |
| Auto key refresh | `RoadflareTab.kt:257-307` | Send Kind 3188 with `status="stale"` on stale key detection |
| Key refresh handler | `MainActivity.kt:367-428` | Driver re-sends key when ack has status≠"received" or stale timestamp |

### Key API Changes

**RoadflareKeyAckEvent.create()** - Added `status` parameter (default="received"):
```kotlin
// Normal ack:
create(signer, driverPubKey, keyVersion, keyUpdatedAt)
// Refresh request:
create(signer, driverPubKey, keyVersion, keyUpdatedAt, status = "stale")
```

**NostrService.publishRoadflareKeyAck()** - Added `status` parameter:
```kotlin
publishRoadflareKeyAck(driverPubKey, keyVersion, keyUpdatedAt, status = "stale")
```

### Auto-Sync on Go-Online (Cross-Device Fix)

```kotlin
// DriverViewModel.kt:3801
private suspend fun ensureRoadflareStateSynced(): Boolean {
    val currentState = driverRoadflareRepository.state.value
    if (currentState?.roadflareKey == null && currentState?.followers.isNullOrEmpty()) {
        val remoteState = nostrService.fetchDriverRoadflareState()
        if (remoteState != null) {
            driverRoadflareRepository.restoreFromBackup(remoteState)
            return true
        }
    }
    return false
}
```

Called from `proceedGoOnline()` and `goRoadflareOnly()` via `viewModelScope.launch`.

### Out-of-Order Event Rejection

```kotlin
// RoadflareTab.kt:208-235
val eventCreatedAt = event.createdAt
val isExpired = RoadflareLocationEvent.isExpired(event)
val lastSeen = lastLocationCreatedAt[driverPubKey] ?: 0L
val isOutOfOrder = eventCreatedAt < lastSeen

if (!isExpired && !isOutOfOrder) {
    lastLocationCreatedAt[driverPubKey] = eventCreatedAt
    // Update driverLocations...
}
```

### Key Refresh Flow

```
Rider detects stale key (checkStaleKeys):
1. storedKeyUpdatedAt < currentKeyUpdatedAt from Kind 30012
2. Rate-limit: 1 request per hour per driver
3. Send Kind 3188 with status="stale"

Driver receives Kind 3188:
1. Verify pubkey authorship (ackData.riderPubKey == event.pubKey)
2. Verify follower authorized (approved + not muted)
3. If status != "received" OR keyUpdatedAt < currentKeyUpdatedAt:
   → Re-send key via roadflareKeyManager.sendKeyToFollower()
```

## Profile Sync Architecture

### Overview
ProfileSyncManager coordinates all profile data sync with Nostr relays:
- Ordered sync on key import (wallet → profile → history)
- Shared KeyManager instance (avoids multiple KeyManager race conditions)
- Observable sync state for UI feedback

### Sync Order (on key import)
1. **Wallet (order=0)** - Highest priority, needed for payments
2. **Profile (order=1)** - Unified: vehicles, saved locations, settings (Kind 30177)
3. **Ride History (order=2)** - May reference payments

### Profile Backup (Kind 30177)
ProfileSyncAdapter syncs vehicles, saved locations, AND settings in a single event:
- Driver app: vehicles + settings
- Rider app: saved locations + settings
- Auto-migrates from legacy 30175/30176 events

### Adding New Sync Features
Profile data is now consolidated. To add new settings or data to profile backup:
1. Add field to `SettingsBackup` or data to `ProfileBackupEvent`
2. Update `SettingsManager.toBackupData()` / `restoreFromBackup()`
3. Test: Fresh install → import key → verify restore

## Multi-Mint Support (Issue #13)

See [GitHub Issue #13](https://github.com/variablefate/ridestr/issues/13) and plan at `~/.claude/plans/zippy-seeking-patterson.md`.

### Phase 1: Protocol & Data Model ✅ COMPLETE (January 2026)

**PaymentMethod enum** (`RideshareEventKinds.kt`):
- `CASHU` - Cashu ecash (NUT-14 HTLC)
- `LIGHTNING` - Lightning Network direct
- `FIAT_CASH` - Cash on delivery

**Fields added to events:**
| Event | Fields Added |
|-------|-------------|
| Kind 30173 (Availability) | `mint_url`, `payment_methods[]` |
| Kind 3173 (Offer) | `mint_url`, `payment_method` |
| Kind 3174 (Acceptance) | `mint_url`, `payment_method` |
| Kind 30177 (Profile) | `settings.paymentMethods[]`, `settings.defaultPaymentMethod`, `settings.mintUrl` |

**SettingsManager** - Added storage for payment preferences with StateFlows.

### Phase 2-3: Planned

**ViewModel Integration:**
- Pass mint_url/payment_methods through NostrService
- PaymentPath detection (SAME_MINT vs CROSS_MINT)
- Driver filtering by compatible payment methods

**Cross-Mint Bridge:**
- `getMintQuoteAtMint()` - HTTP to external mint
- `bridgePayment()` - Melt at rider's mint → Lightning → Deposit at driver's mint
- Execute at pickup (PIN verification)

### Future Enhancements

**Cross-App Portability:**
- `protocol_version` field for version negotiation
- `ext_*` prefix convention for app-specific fields
- Apps MUST ignore unknown extension fields

**Public Profile (Proposed Kind 30178):**
- Optional public driver/rider profile for discoverability
- Cross-app reputation sharing
- Payment method advertisement
