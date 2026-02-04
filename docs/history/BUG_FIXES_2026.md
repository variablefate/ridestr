# Bug Fixes & Implementation Details - 2026

This file archives historical debugging insights, fix details, and implementation notes from the 2026 development cycle. For current architecture, see [CLAUDE.md](../../.claude/CLAUDE.md).

## Table of Contents
- [PaymentHash Migration](#paymenthash-migration-january-2026)
- [NIP-60 Cross-App Wallet Safety](#nip-60-cross-app-wallet-safety-january-2026)
- [Payment Debugging Learnings](#payment-debugging-learnings)
- [RoadFlare Status Detection](#roadflare-status-detection-january-2026)
- [Driver Availability Bouncing](#driver-availability-state-bouncing-fix-february-2026)
- [Shared UI Refactoring](#shared-ui-components-january-2026-refactoring)
- [Encryption Fallback Warning](#encryption-fallback-warning)
- [Security Hardening Details](#security-hardening-details)
- [Dead Code Removed](#dead-code-removed)

---

## PaymentHash Migration (January 2026)

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
- `RiderViewModel.kt` - `confirmRide()` passes paymentHash + escrowToken
- `DriverViewModel.kt` - Extracts paymentHash from `confirmation.paymentHash`
- `DriverViewModel.kt` - `activePaymentHash = null` in acceptOffer() (comes from confirmation now)

---

## NIP-60 Cross-App Wallet Safety (January 2026)

**Problem**: Users importing Nostr keys with existing NIP-60 wallet data from other apps (Minibits, nutstash, etc.) could accidentally lose funds.

**Risks Fixed**:
1. **"Start Fresh" had no warning** - Users could overwrite existing wallet backup without realizing
2. **Cross-mint proof deletion** - `syncNip60WithMint()` deleted ALL proofs when balance mismatch, even from other mints
3. **Metadata overwrite** - `publishWalletMetadata()` silently replaced other app's backup

**Solutions**:
- `WalletSetupScreen.kt` - Confirmation dialog when choosing "Start Fresh" with existing wallet
- `WalletService.kt` - Only delete proofs from CURRENT mint that are verified SPENT
- `Nip60WalletSync.kt` - `getExistingWalletMetadata()` checks before overwrite, `forceOverwrite` parameter

**Key Files**:
- `WalletSetupScreen.kt` - `showStartFreshConfirmation`, `showRestoreFailedDialog` states
- `Nip60WalletSync.kt` - `ExistingWalletInfo` data class, `getExistingWalletMetadata()` method

---

## Payment Debugging Learnings

### 1. cdk-kotlin BLOB Storage
SQLite stores proof `C` field as BLOB (raw bytes), not hex string. Fix: Hex-encode in `findColumnValue()`.

### 2. Keyset Overflow
Some mints return amounts like `2^63` which overflows Long. Fix: `toLongOrNull()` with skip.

### 3. hashToCurve Input
HTLC secrets are JSON strings (e.g., `["HTLC",{...}]`), not hex. Hash directly as UTF-8.

### 4. NIP-60 Auto-Sync
If NIP-60 proofs insufficient but cdk-kotlin has enough, auto-sync before retrying.

### 5. hashToCurve Algorithm (NUT-00)
Uses **double SHA256**, not single:
- `msg_hash = SHA256(DOMAIN_SEPARATOR || message)`
- `final = SHA256(msg_hash || counter)` ← counter is 4-byte little-endian
- Wrong Y values cause NUT-07 verification to check non-existent proofs

### 6. HTLC Refund Preimage Storage (January 2026)
`PendingHtlc` now stores the preimage for future-proof refunds. Some mints don't verify the hash (allowing zeros workaround), but if they fix this, refunds need the real preimage. Preimage threaded through `lockForRide()` → `PendingHtlc` → `refundExpiredHtlc()`. Old HTLCs without preimage fall back to zeros.

### 7. Duplicate Confirmation Race Condition (January 2026)
When acceptance arrives, `autoConfirmRide()` launches async. If user taps manual confirm button during async window, TWO Kind 3175 events sent → driver stores first, rider stores second → different `confirmationEventId` → all subsequent events filtered as "different ride". **Fix**: Set `isConfirmingRide = true` BEFORE coroutine launch + add guards in `confirmRide()`.

### 8. Unblinding Key Lookup (CDK Pattern)
When unblinding signatures from mint, use `responseAmount` from the mint's BlindSignature response to look up the public key, NOT `pms.amount` from our PreMintSecret. The mint returns `{amount, id, C_}` for each signature - use that `amount` for `keyset.keys[responseAmount]`. If mint reorders responses or assigns different amounts, using our premint amount would select wrong key → invalid unblinded proof.

### 9. Correlation ID Logging (February 2026)
Payment operations now log with ride correlation IDs for traceability:
- RiderViewModel: `[RIDE xxxxxxxx] Locking HTLC: fareAmount=X, paymentHash=Y...`
- DriverViewModel: `[RIDE xxxxxxxx] Claiming HTLC: paymentHash=X...`
- Uses `acceptanceEventId.take(8)` (rider) or `confirmationEventId.take(8)` (driver)

---

## RoadFlare Status Detection (January 2026)

### Problem Summary
RoadFlare screens showed drivers as offline even when online due to:
1. **Cross-device sync gap**: State only synced during onboarding, not on go-online
2. **Out-of-order events**: Late OFFLINE could override newer ONLINE
3. **Stale keys**: Rider has outdated key after driver rotated

### Fixes Reference

| Fix | File | Method |
|-----|------|--------|
| Cross-device sync | `DriverViewModel.kt` | `ensureRoadflareStateSynced()` |
| Out-of-order rejection | `RoadflareTab.kt` | `lastLocationCreatedAt` tracking |
| Staleness filter | `RiderModeScreen.kt` | 10-min freshness check |
| Key refresh | `RoadflareTab.kt` | Kind 3188 with `status="stale"` |

### Key Refresh API

```kotlin
// Request refresh (rider):
NostrService.publishRoadflareKeyAck(driverPubKey, keyVersion, keyUpdatedAt, status = "stale")

// Handler (driver - MainActivity.kt):
// 1. Verify pubkey authorship
// 2. Verify follower authorized (approved + not muted)
// 3. Re-send key via roadflareKeyManager.sendKeyToFollower()
```

---

## Driver Availability State Bouncing Fix (February 2026)

### Problem Summary
Driver status bounced between offline/online in the rider app when:
1. Switching menus/tabs caused state to reset
2. Accepting rides then immediately seeing "driver not available" dialog

### Root Causes Fixed

| Cause | Fix | File |
|-------|-----|------|
| Aggressive driver list clearing on ALL resubscribes | Added `clearExisting` parameter - only clear on geohash changes | `RiderViewModel.kt` |
| Stale cleanup used `createdAt` (publish time) not receive time | Added `driverLastReceivedAt` map for accurate staleness | `RiderViewModel.kt` |
| Out-of-order availability events | Added `selectedDriverLastAvailabilityTimestamp` guard | `RiderViewModel.kt` |
| Very old events from relay history | Added 10-min `since` filter to subscription | `NostrService.kt` |
| Stale cleanup during active ride | Pause cleanup when not in IDLE stage | `RiderViewModel.kt` |

### Key Changes

**1. `resubscribeToDrivers(clearExisting: Boolean = false)`**
- Only clears driver list when `clearExisting = true` (geohash/region changed)
- Same-region refreshes (toggle expanded search, cancel ride, etc.) preserve existing drivers
- Call sites: 2 with `true` (geohash changes), 6 with `false` (same region)

**2. `driverLastReceivedAt` Map**
- Tracks when events were actually received vs when published
- Updated at all driver mutation points (add, update, remove)
- Used in `cleanupStaleDrivers()` for accurate freshness detection

**3. Timestamp Guard for Selected Driver**
```kotlin
if (availability.createdAt < selectedDriverLastAvailabilityTimestamp) {
    Log.d(TAG, "Ignoring out-of-order availability event")
    return@subscribeToDriverAvailability
}
selectedDriverLastAvailabilityTimestamp = availability.createdAt
```

**4. Stale Cleanup Paused During Rides**
```kotlin
if (stage != RideStage.IDLE) {
    return  // Silent skip - cleanup will resume when back to IDLE
}
```

---

## Shared UI Components (January 2026 Refactoring)

### Overview
Duplicated UI code was extracted from both apps into the common module to reduce maintenance burden.

### Extracted Components

| File | Components | Savings |
|------|------------|---------|
| `common/.../ui/screens/KeyBackupScreen.kt` | Key backup display (100% identical) | ~150 lines |
| `common/.../ui/screens/ProfileSetupContent.kt` | Profile editing form | ~170 lines |
| `common/.../ui/screens/OnboardingComponents.kt` | `KeySetupScreen`, `BackupReminderScreen` | ~270 lines |
| `common/.../ui/components/SettingsComponents.kt` | `SettingsSwitchRow`, `SettingsNavigationRow`, `SettingsActionRow` | ~145 lines |

### Pattern: Wrapper/Content Split

For screens that need app-specific ViewModels, we use the **wrapper/content pattern**:
- **Content composable** (in common): Takes primitive params, no app-specific types
- **Wrapper composable** (in each app): Connects ViewModel to content

```kotlin
// In common: ProfileSetupContent.kt
@Composable
fun ProfileSetupContent(
    npub: String?,                    // Primitives, not UiState
    displayName: String,
    roleDescriptionText: String,      // App-specific customization
    aboutPlaceholderText: String,
    onSave: () -> Unit,
    // ...
)

// In rider-app: ProfileSetupScreen.kt
@Composable
fun ProfileSetupScreen(viewModel: ProfileViewModel, ...) {
    val uiState by viewModel.uiState.collectAsState()
    ProfileSetupContent(
        npub = uiState.npub,
        roleDescriptionText = "Tell drivers about yourself",
        // ...
    )
}
```

### App-Specific Customization

| Component | Rider App | Driver App |
|-----------|-----------|------------|
| ProfileSetupContent | "Tell drivers about yourself" | "Tell riders about yourself" |
| KeySetupScreen | "Welcome to Ridestr" | "Welcome to Drivestr" |
| SettingsSwitchRow | `enabled = true` (default) | Uses `enabled` param with alpha styling |

---

## Encryption Fallback Warning

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
- `SecureKeyStorage.kt` - Flag + getter
- `WalletKeyManager.kt` - Flag + getter
- `WalletStorage.kt` - Flag + getter
- `SettingsManager.kt` - Dismissal prefs
- `rider-app/MainActivity.kt` - Warning check + dialog
- `drivestr/MainActivity.kt` - Warning check + dialog

---

## Security Hardening Details

### Backup Exclusions

**Files:** `*/res/xml/backup_rules.xml` and `*/res/xml/data_extraction_rules.xml` (both apps)

**Excluded SharedPreferences:**
- **Secrets:** `ridestr_secure_keys.xml`, `ridestr_wallet_keys.xml`, `ridestr_wallet_storage.xml`, `ridestr_settings.xml`
- **Privacy Data:** `ridestr_ride_history.xml`, `ridestr_saved_locations.xml`, `ridestr_vehicles.xml`, `roadflare_*.xml`

**Rationale:** Nostr sync (Kind 30174, 30177) is the recovery path. Cloud backup exclusion prevents accidental key exposure.

### Pubkey Validation Wiring

**NostrService.kt method signatures:**
```kotlin
subscribeToAcceptance(offerEventId, expectedDriverPubKey, onAcceptance)  // Direct offers
subscribeToConfirmation(acceptanceEventId, scope, expectedRiderPubKey, onConfirmation)  // Confirmations
```

**Call sites:**
- `RiderViewModel`: Multiple call sites pass `driverPubKey`
- `DriverViewModel`: Multiple call sites pass `riderPubKey`
- State subscriptions pass expected pubkey to parsers

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

---

## Dead Code Removed

- `WalletService.settleRide()` - orphaned method, deleted
- UI buttons "Verify Balance" and "Resync Proofs to NIP-60" - redundant with `syncWallet()`
