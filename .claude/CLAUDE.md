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
| Multi-Mint Protocol | ✅ COMPLETE (all phases: fields, filtering, cross-mint bridge) |
| State Machine | ✅ COMPLETE (AtoB pattern, driver broadcasts state) |
| Relay Optimizations | ✅ COMPLETE (EOSE-aware queries, progressive backoff) |
| Wallet Auto-Refresh | ✅ COMPLETE (balance sync after all major payments) |
| Remote Config | ✅ COMPLETE (Kind 30182, fare rates from admin pubkey) |
| NIP-60 Cross-App Safety | ✅ COMPLETE (cross-mint proof preservation, metadata protection) |
| Security Hardening | ✅ COMPLETE (backup exclusions, pubkey validation, WebSocket concurrency, signature verification) |
| RoadFlare Status Detection | ✅ COMPLETE (auto-sync, out-of-order rejection, staleness filter, key refresh) |
| Driver Availability Stability | ✅ COMPLETE (selective clearing, receivedAt tracking, timestamp guards) |
| NostrService Domain Decomposition | ✅ COMPLETE (4 domain services + CashuTokenCodec) |
| Payment Test Harness | ✅ COMPLETE (Robolectric + MockK) |
| Nip60Store Interface | ✅ COMPLETE (testable abstraction for NIP-60 operations) |
| Proof-Conservation Tests | ✅ COMPLETE (contract tests for proof safety invariants) |
| State Reset Consolidation | ✅ COMPLETE (single resetRideUiState() in each ViewModel) |
| RideSession Object | ✅ COMPLETE (ride-scoped fields in nested data class, auto-reset by construction) |
| SubscriptionManager | ✅ COMPLETE (centralized lifecycle, SubKeys constants, auto-close on replace) |
| Double-Confirmation Race Guard | ✅ COMPLETE (AtomicBoolean CAS, StateFlow CAS stage transitions, acceptance-identity post-suspension guards) |
| LogoutManager | ✅ COMPLETE (16 cleanup operations, ViewModel job cancellation) |
| Batch Offer Cancellation | ✅ COMPLETE (NIP-09 deletion, payment method selection, balance precheck, FrozenRideInputs) |
| Payment Method Priority (Issue #46) | ✅ COMPLETE (drag-to-reorder UI, fiat_payment_methods wiring, case-insensitive matching, driver-side compatibility indicator) |
| Escrow Bypass Block | ✅ COMPLETE (rider blocks confirmation, driver hides "Complete Anyway" for SAME_MINT, process-death-stable paymentPath) |

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

## Workflow Orchestration

### 1. Plan Mode Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately — don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy (Keep Main Context Clean)
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One task per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `.claude/tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes — don't over-engineer
- Challenge your own work before presenting it

## Task Management
1. **Plan First**: Write plan to `.claude/tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review to `.claude/tasks/todo.md`
6. **Capture Lessons**: Update `.claude/tasks/lessons.md` after corrections

## Core Principles
- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

## Common Tasks → Documentation Path

| Task | Start Here | Then Read |
|------|------------|-----------|
| Debug data issue | This file "Critical Debugging" | `.claude/skills/ridestr-protocol/SKILL.md` |
| Add new sync feature | `docs/guides/ADDING_NOSTR_SYNC.md` | `.claude/skills/add-nostr-sync.md` |
| Payment debugging | `docs/architecture/PAYMENT_ARCHITECTURE.md` | `.claude/skills/cashu-wallet.md` |
| Modify payment code | `docs/architecture/PAYMENT_SAFETY.md` | `docs/architecture/COMPATIBILITY_CONTRACTS.md` |
| Understand ride flow | `docs/architecture/STATE_MACHINES.md` | `.claude/skills/ridestr-protocol/SKILL.md` |
| Event structure | `docs/protocol/NOSTR_EVENTS.md` | Event source files in `common/.../events/` |
| Debug RoadFlare issue | This file "RoadFlare Architecture" | `docs/CONNECTIONS.md#roadflare-system` |

## Ride Flow Overview

> **Full sequence diagrams**: See [docs/CONNECTIONS.md](../docs/CONNECTIONS.md#offer-types-comparison)

### Offer Type Comparison

| Aspect | Direct Offer | Broadcast Offer | RoadFlare Offer |
|--------|--------------|-----------------|-----------------|
| **Kind 3173 Content** | NIP-44 encrypted | Plaintext (~1km approx) | NIP-44 encrypted |
| **Target** | Specific driver (p-tag) | All in area (g-tags) | Specific driver (p-tag + roadflare tag) |
| **Driver Discovery** | Kind 30173 availability | Kind 30173 + geohash | Kind 30014 encrypted location |
| **Privacy** | High | Medium (approximate location public) | High |
| **Recommended** | ✅ Primary method | ⚠️ Advanced/Discouraged | ✅ Primary for trusted network |
| **Pre-requisites** | Driver broadcasting | Route calculated | Follower approved + key received |

### Key Methods by Offer Type

| Type | RiderViewModel | NostrService |
|------|---------------|--------------|
| Direct | `sendRideOffer()` | `sendOffer(RideOfferSpec.Direct(...))` |
| Broadcast | `broadcastRideRequest()` | `sendOffer(RideOfferSpec.Broadcast(...))` |
| RoadFlare (single) | `sendRoadflareOffer()` | `sendOffer(RideOfferSpec.RoadFlare(...))` |
| RoadFlare (batch) | `sendRoadflareToAll(...)` | `sendOffer(RideOfferSpec.RoadFlare(...))` (batched) |

**Broadcast Privacy Note:** Location approximation (~1km precision) is applied inside `RideOfferSpec.Broadcast`. UI shows privacy warning dialog before allowing broadcast.

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

### RoadFlare Events
| Kind | Type | Purpose |
|------|------|---------|
| 30011 | Param. Replaceable | Followed Drivers (rider's favorites + keys) |
| 30012 | Param. Replaceable | Driver RoadFlare State (keypair, followers, muted) |
| 30014 | Param. Replaceable | Location Broadcast (encrypted, 5-min expiry) |
| 3186 | Regular | Key Share (driver→follower DM, 5-min expiry) |
| 3187 | Regular | Follow Notification (short expiry, real-time UX; p-tag query is primary) |
| 3188 | Regular | Key Acknowledgement (confirm receipt or refresh request) |

## RoadFlare Architecture

> **Full diagrams**: See [docs/CONNECTIONS.md](../docs/CONNECTIONS.md#roadflare-system)

RoadFlare enables riders to build a **personal rideshare network** of trusted drivers. Drivers broadcast encrypted locations; only approved followers can see them.

### Encryption Model (Shared Keypair)
```
Driver broadcasts:    nip44Encrypt(location, roadflarePubKey)
Follower decrypts:    nip44Decrypt(ciphertext, driverPubKey) using stored roadflarePrivKey
ECDH commutative:     ECDH(driver_priv, roadflare_pub) == ECDH(roadflare_priv, driver_pub)
```
**Efficiency**: 1 encryption for N followers (vs N separate NIP-44 DMs)

### Follower Lifecycle
```
PENDING → APPROVED → KEY_SENT → ACTIVE
                         ↓ (driver "Remove")
                       MUTED → key rotation → can restore later
```
**Key insight:** Driver "Remove" = mute (preserves data), Rider "Remove" = actual delete.

### Driver Availability States

| Mode | Kind 30173 | Kind 30014 | Offers Received |
|------|-----------|-----------|-----------------|
| **OFFLINE** | None | None | None |
| **ROADFLARE_ONLY** | No location (privacy) | Broadcasting | Only `roadflare`-tagged |
| **AVAILABLE** | Has location + geohash | Broadcasting | All offers |
| **ON_RIDE** | Stopped (NIP-09 deleted) | `ON_RIDE` status | None |

### Key Files
| Component | File | Key Methods |
|-----------|------|-------------|
| RoadFlare domain service | `RoadflareDomainService.kt` | All RoadFlare Nostr methods |
| Follower management | `RoadflareKeyManager.kt` | `approveFollower()`, `handleMuteFollower()`, `rotateKey()` |
| Location broadcast | `RoadflareLocationBroadcaster.kt` | `startBroadcasting()`, `setOnRide()` |
| Cross-device sync | `DriverViewModel.kt` | `ensureRoadflareStateSynced()` |
| Stale key detection | `RoadflareTab.kt` | `checkStaleKeys()` |
| Key refresh handler | `MainActivity.kt` | Kind 3188 handler - rider sends `status="stale"`, driver re-sends key |

## Payment System

### Payment Methods

| Method | Status | Implementation |
|--------|--------|----------------|
| **Cashu HTLC** | ✅ Working | NUT-14 escrow with deferred locking |
| **Lightning Direct** | 🚧 Planned | Driver invoice, rider pays |
| **Fiat Cash** | 🚧 Planned | Cash on delivery, trust-based |
| **RoadFlare Alternates** | ✅ Working | Zelle, PayPal, Cash, etc. (no escrow) |

### ⚠️ CRITICAL: Nostr Key ≠ Wallet Key

For security isolation, identity and payment use **different keys**:
- **Nostr key**: User identity, event signing, NIP-44 encryption
- **Wallet key**: P2PK escrow claims, BIP-340 Schnorr signatures

Driver's `wallet_pubkey` in Kind 3174 ensures HTLC is locked to correct key. If you use the wrong key, HTLC claims will fail.

### ⚠️ CRITICAL: PaymentHash is in Kind 3175, NOT Kind 3173

**PaymentHash is in confirmation (Kind 3175), NOT offer (Kind 3173).** This is intentional - HTLC must lock AFTER acceptance to use driver's wallet_pubkey. Do NOT move paymentHash back to offer events.

### Deferred HTLC Locking

HTLC is locked AFTER driver accepts (in `autoConfirmRide()`), using driver's `wallet_pubkey` from acceptance. This ensures the P2PK key matches what the driver can sign with.

### ⚠️ CRITICAL: NIP-60 is Source of Truth

**NIP-60 IS the wallet.** cdk-kotlin is only used for mint API calls (deposit/withdraw). `WalletService.syncWallet()` fetches all NIP-60 proofs, verifies at mint, cleans spent proofs, and updates balance.

### NUT-07 Stale Proof Verification

`lockForRide()` verifies proofs with mint BEFORE HTLC swap. If spent proofs found, deletes stale NIP-60 events and retries (up to 3 attempts). Returns `ProofsSpent` failure if cleanup fails.

### Automatic Wallet Refresh

| Operation | Location | What Happens |
|-----------|----------|--------------|
| HTLC Claim (driver) | `WalletService.claimHtlcPayment()` | Publishes proofs → fetches fresh → `updateDiagnostics()` |
| Ride Completion (rider) | `RiderViewModel.handleRideCompletion()` | `markHtlcClaimedByPaymentHash()` → `refreshBalance()` |
| Ride Cancellation (rider) | `RiderViewModel.handleDriverCancellation()` | `refreshBalance()` (checks for expired HTLC refunds) |
| Withdrawal | `WalletService.executeWithdraw()` | NIP-60 fetch via `meltWithProofs()` |
| Cross-Mint Bridge | `WalletService.bridgePayment()` | NIP-60 fetch after deposit claim |

### Key Payment Debugging Learnings

1. **hashToCurve Input**: HTLC secrets are JSON strings (e.g., `["HTLC",{...}]`), not hex. Hash directly as UTF-8.
2. **hashToCurve Algorithm (NUT-00)**: Uses **double SHA256**, not single. Wrong Y values cause NUT-07 verification to check non-existent proofs.
3. **Unblinding Key Lookup (CDK Pattern)**: Use `responseAmount` from mint's BlindSignature response to look up public key, NOT `pms.amount` from PreMintSecret.

> **Full debugging history**: See [docs/history/BUG_FIXES_2026.md](../docs/history/BUG_FIXES_2026.md)

## Critical Debugging

**When debugging data issues, ALWAYS trace data at BOTH:**
1. **ORIGINATION** (where data is created/sent)
2. **RECEIVER** (where data is processed)

Example: Phantom cancellation bug was in DRIVER app (origination) not RIDER app (receiver).

## State Management Rules

- Kind 30180/30181 use `history` arrays that ACCUMULATE actions
- **CRITICAL**: Clear history when starting new ride (`clearDriverStateHistory()`, `clearRiderStateHistory()`)
- d-tag format: `ridestr-{confirmationEventId}` for subscription filtering

### Consolidated State Resets (Phase 1 + Phase 3 RideSession)

Both ViewModels use **nested `RideSession` data classes** (`RiderRideSession`, `DriverRideSession`). `resetRideUiState()` resets `rideSession` to defaults — any new field added to the session class is automatically included in reset by construction. Do NOT scatter field resets across multiple `.copy()` calls.

**Access patterns:**
- Pure session updates: `updateRideSession { copy(fieldName = value) }` (atomic `StateFlow.update {}`)
- Mixed updates (session + outer): `_uiState.update { current -> current.copy(statusMessage = ..., rideSession = current.rideSession.copy(...)) }`
- UI reads: `uiState.rideSession.fieldName` for ride-scoped fields

**SubscriptionManager (Phase 4):**
Both ViewModels use `SubscriptionManager` (`common/nostr/SubscriptionManager.kt`) — a centralized registry that replaces scattered nullable subscription ID variables. Each ViewModel defines a `SubKeys` object with string constants. `subs.set(key, id)` auto-closes the previous subscription for that key. `subs.closeAll(*keys)` for selective cleanup; `subs.closeAll()` (nuclear) for `onCleared()`. Group subscriptions (`setInGroup`/`closeGroup`) handle N-to-1 patterns like rider profiles keyed by pubkey.

**Subscription cleanup (RiderViewModel):**
- `closeAllRideSubscriptionsAndJobs()` — closes `SubKeys.RIDE_ALL` (5 subs) + cancels all jobs (including escrow retry and post-confirm ack timeouts). Used by all ride-ending paths.

**DriverViewModel** has only `closeAllRideSubscriptionsAndJobs()` — closes `SubKeys.RIDE_ALL` (4 subs) + cancels 3 jobs. Does NOT close offer/broadcast/deletion subscriptions (those are managed by the availability lifecycle). `proceedGoOnline()` calls `updateDeletionSubscription()` after re-subscribing to offers to re-establish the DELETION watcher for any pending items that survived offline.

**Pattern for ride-ending paths:**
```
val state = _uiState.value  // Capture BEFORE reset if async history needs pre-reset values
closeAllRideSubscriptionsAndJobs()
clearDriverStateHistory()  // or clearRiderStateHistory()
resetRideUiState(stage = ..., statusMessage = "...")
clearSavedRideState()
```

**Exception**: `handleRideCompletion()` (rider) and `completeRideInternal()` (driver) use partial `.copy()` instead of full reset — the completion screen needs ride data visible. Full reset happens later via `clearRide()` / `finishAndGoOnline()` / `clearAcceptedOffer()`.

**Double-Confirmation Race Guard (Phase 6):**
Multi-relay delivery of acceptance events causes concurrent `autoConfirmRide()` calls on different IO threads (`RelayConnection` uses `Dispatchers.IO`, `RelayManager.handleEvent()` invokes callbacks synchronously). Guards:
- `confirmationInFlight` AtomicBoolean — CAS gate (`compareAndSet(false, true)`) as the very first operation in `autoConfirmRide()` and `confirmRide()`. Only one caller wins. Stays `true` for ride lifetime; reset in `resetRideUiState()`.
- `hasAcceptedDriver` AtomicBoolean — `compareAndSet` for broadcast first-acceptance-wins (lazy reset in `setupOfferSubscriptions`).
- Callback stage checks use `StateFlow.update {}` CAS with `shouldConfirm` re-derived from `current` at lambda top (safe under CAS retry).
- Post-suspension guards check acceptance identity FIRST (prevents cross-ride contamination), then stage (same-ride cancel). Mismatch uses targeted Kind 3179 cancel only — NOT global `cleanupRideEventsInBackground()` (which is author-scoped, not ride-scoped).
- try/catch with `CancellationException` rethrow wraps confirmation coroutines. Catch blocks guard `confirmationInFlight` reset with acceptance identity check.

## Key Files Reference

### Ride State Management
- `SubscriptionManager.kt` - Centralized subscription ID lifecycle (`set()`, `close()`, `closeAll()`, `setInGroup()`, `closeGroup()`)
- `DriverViewModel.kt` - `resetRideUiState()`, `closeAllRideSubscriptionsAndJobs()`, `clearDriverStateHistory()`, `acceptOffer()`, `acceptBroadcastRequest()`
- `RiderViewModel.kt` - `resetRideUiState()`, `closeAllRideSubscriptionsAndJobs()`, `clearRiderStateHistory()`
- `NostrService.kt` - Facade that delegates to domain services (backward compatible)
- `RideshareDomainService.kt` - Ride protocol events (Kind 3173-3179, 30173, 30180-30181)
- `NostrCryptoHelper.kt` - NIP-44 encryption utilities
- `ProfileBackupService.kt` - Profile and history backup (Kind 30174, 30177)
- `RoadflareDomainService.kt` - RoadFlare events (Kind 30011, 30012, 30014, 3186, 3188)

### State Machine
- `common/.../state/RideState.kt` - Unified state enum
- `common/.../state/RideEvent.kt` - Event types
- `common/.../state/RideStateMachine.kt` - Processor with `processEvent()`, `canTransition()`
- ViewModels call `validateTransition()` before state changes - logs warnings but doesn't block

### Pre-Confirmation Driver Monitoring (Issue #22)
When rider sends a direct offer, the rider app monitors that driver's availability via `subscribeToSelectedDriverAvailability()`. If driver goes offline → shows "Driver Unavailable" dialog → auto-cancels → returns to IDLE.

### Logout
- `LogoutManager.kt` - Centralized cleanup (16 operations) called by both MainActivity files on logout
- `DriverViewModel.performLogoutCleanup()` / `RiderViewModel.performLogoutCleanup()` - Cancel ViewModel-owned jobs before Activity destruction

### Profile Sync
- `ProfileSyncManager.kt` - Central sync orchestrator
- `Nip60WalletSyncAdapter.kt` - Wallet sync (order=0)
- `ProfileSyncAdapter.kt` - Unified profile sync (order=1) - vehicles, locations, settings
- `RideHistorySyncAdapter.kt` - Ride history sync (order=2)

### Payment
- `CashuBackend.kt` - Mint operations, HTLC, P2PK signing, NUT-09 restore
- `CashuTokenCodec.kt` - Stateless token encoding/decoding utilities
- `WalletService.kt` - Orchestration layer, `recoverFromSeed()`, `lockForRide()`, `claimHtlcPayment()`
- `Nip60WalletSync.kt` - NIP-60 sync, implements `Nip60Store` interface
- `WalletKeyManager.kt` - Wallet keys with Schnorr signing (`signSchnorr()`)
- `PaymentCrypto.kt` - Preimage/hash generation
- `CashuCrypto.kt` - secp256k1 crypto, NUT-13 deterministic derivation

### Test Infrastructure
- `FakeMintApi.kt` - Mock mint HTTP API with queue-based responses
- `FakeNip60Store.kt` - Mock NIP-60 storage with call log for order verification
- `MainDispatcherRule.kt` - JUnit rule for Dispatchers.Main override in tests

**Run tests:** Use the build skill with `test` argument or run `run_tests.bat`

## Security Features

| Component | Status |
|-----------|--------|
| Backup Exclusions | ✅ Fixed - correct SharedPrefs filenames + privacy data |
| Pubkey Validation | ✅ Wired - expectedDriverPubKey/expectedRiderPubKey |
| WebSocket Concurrency | ✅ Hardened - bounded channel, generation tracking |
| Signature Verification | ✅ Implemented - relay-level event.verify() |
| Encryption Fallback Warning | ✅ Implemented - warns user when storage falls back to plaintext |
| TalkBack Accessibility | ✅ Implemented - semantic roles, content descriptions, click labels |

### Signature Verification Flow
```
Relay message → RelayConnection.handleMessage()
    → event.verify() (NIP-01 Schnorr) → reject if invalid
    → Parser with expectedPubKey validation → reject if mismatch
    → ViewModel receives validated data
```

> **Security details**: See [docs/history/BUG_FIXES_2026.md](../docs/history/BUG_FIXES_2026.md#security-hardening-details)

## Profile Sync Architecture

### Sync Order (on key import)
1. **Wallet (order=0)** - Highest priority, needed for payments
2. **Profile (order=1)** - Unified: vehicles, saved locations, settings (Kind 30177)
3. **Ride History (order=2)** - May reference payments

### Multi-App Safety

`ProfileSyncAdapter.publishToNostr()` uses **read-before-write pattern**:
- Fetches existing Kind 30177 before publishing
- Preserves other app's data (vehicles/locations)
- Never overwrites cross-app data

`RideHistoryEntry.appOrigin` tags each ride with `"ridestr"` or `"drivestr"` to filter in UI.

### Adding New Sync Features
1. Add field to `SettingsBackup` or data to `ProfileBackupEvent`
2. Update `SettingsManager.toBackupData()` / `restoreFromBackup()`
3. Test: Fresh install → import key → verify restore

## Multi-Mint Support

**Status: ✅ COMPLETE** - All phases implemented.

**PaymentMethod enum** (`RideshareEventKinds.kt`): `CASHU`, `LIGHTNING`, `FIAT_CASH`

**PaymentPath detection**: `SAME_MINT` | `CROSS_MINT` | `FIAT_CASH` | `NO_PAYMENT`

**Cross-Mint Bridge**: `WalletService.bridgePayment()` - Melt at rider's mint → Lightning → Deposit at driver's mint (executed at pickup after PIN verification)

**Fields in events:**
| Event | Fields |
|-------|--------|
| Kind 30173 (Availability) | `mint_url`, `payment_methods[]` |
| Kind 3173 (Offer) | `mint_url`, `payment_method` |
| Kind 3174 (Acceptance) | `mint_url`, `payment_method` |
| Kind 30177 (Profile) | `settings.paymentMethods[]`, `settings.defaultPaymentMethod`, `settings.mintUrl` |

## Known TODOs

### ✅ RESOLVED: Escrow Bypass (March 2026)

**Fixed:** If `lockForRide()` fails for SAME_MINT payment, rider app now BLOCKS the ride with a retry/cancel dialog (15s deadline). Driver app hides "Complete Anyway" for SAME_MINT rides. `paymentPath` is persisted across process death for correct enforcement after restart.
