# Rider App Module

## Overview

The rider Android app allows users to request rides, track driver location in real-time, and manage payments via Cashu wallet. All state is centralized in `RiderViewModel`, which coordinates between NostrService for communication and WalletService for payments.

---

## Key Components

### ViewModels (`java/com/ridestr/rider/viewmodels/`)

| File | Purpose | Key State Fields |
|------|---------|------------------|
| `RiderViewModel.kt` | Central state machine (~2800 lines) | `rideStage`, `currentOffer`, `currentDriver`, `riderStateHistory` |
| `OnboardingViewModel.kt` | Key generation/import flow | `uiState`, `isLoggedIn` |
| `ProfileViewModel.kt` | Profile editing | `name`, `profilePicture` |

### Screens (`java/com/ridestr/rider/ui/screens/`)

| File | Purpose |
|------|---------|
| `RiderModeScreen.kt` | Main ride UI - map, pickup/dropoff selection, driver tracking |
| `WalletScreen.kt` | Balance display card - tap opens `WalletDetailScreen` |
| `HistoryScreen.kt` | Past rides list with manual backup button |
| `SettingsScreen.kt` | App settings, relay config, developer options |
| `OnboardingScreen.kt` | Login/signup flow |
| `ProfileSetupScreen.kt` | Profile name and picture setup |
| `KeyBackupScreen.kt` | nsec backup display |
| `TipScreen.kt` | Post-ride tipping UI |

### Services (`java/com/ridestr/rider/service/`)

| File | Purpose |
|------|---------|
| `RiderActiveService.kt` | Foreground service - keeps app alive during active rides |

### Entry Point

| File | Purpose |
|------|---------|
| `MainActivity.kt` | App entry, Compose navigation, dependency injection, sync adapter registration |

---

## State Machine (RiderViewModel)

```
IDLE
  │
  ▼ requestRide() / broadcastRideRequest()
BROADCASTING_REQUEST
  │
  ▼ driver responds with acceptance
WAITING_FOR_ACCEPTANCE
  │
  ▼ rider confirms acceptance
DRIVER_ACCEPTED
  │
  ▼ rider sends confirmation with PIN
RIDE_CONFIRMED
  │
  ▼ driver arrives at pickup
DRIVER_ARRIVED
  │
  ▼ driver verifies PIN, ride starts
IN_PROGRESS
  │
  ▼ arrive at dropoff
COMPLETED

Any state → cancelRide() or driverCancelled() → CANCELLED → IDLE
```

### Key State Transitions

| Method | From State | To State | Nostr Event |
|--------|------------|----------|-------------|
| `broadcastRideRequest()` | IDLE | BROADCASTING_REQUEST | Kind 3173 |
| `confirmRide()` | WAITING_FOR_ACCEPTANCE | RIDE_CONFIRMED | Kind 3175 |
| `handleDriverRideState()` | Various | Various | Receives Kind 30180 |
| `cancelRide()` | Any | CANCELLED | Kind 3179 |
| `clearRiderStateHistory()` | - | - | **CRITICAL**: Must call when starting new ride |

---

## Connections Table

| From (this module) | To (common module) | How/Why | Example Call |
|--------------------|-------------------|---------|--------------|
| `RiderViewModel` | `NostrService` | Publish ride request | `nostrService.broadcastRideRequest()` |
| `RiderViewModel` | `NostrService` | Subscribe to acceptances | `nostrService.subscribeToAcceptances()` |
| `RiderViewModel` | `NostrService` | Publish rider state | `nostrService.publishRiderRideState()` |
| `RiderViewModel` | `WalletService` | Lock funds for ride (HTLC) | `walletService.lockForRide(fareSats)` |
| `RiderViewModel` | `WalletService` | Check balance | `walletService.hasSufficientFunds()` |
| `RiderViewModel` | `RideHistoryRepository` | Save completed ride | `rideHistoryRepo.addRide()` |
| `RiderViewModel` | `SavedLocationRepository` | Recent locations | `savedLocationRepo.addRecent()` |
| `MainActivity` | `ProfileSyncManager` | Register sync adapters | `profileSyncManager.registerSyncable()` |
| `MainActivity` | `Nip60WalletSync` | Wire to WalletService | `walletService.setNip60Sync()` |
| `MainActivity` | `NostrService` | Create service instance | Constructor injection |
| `WalletScreen` | `WalletService` | Display balance | `walletService.balance.value` |
| `HistoryScreen` | `RideHistoryRepository` | Display past rides | `rideHistoryRepo.rides.value` |
| `HistoryScreen` | `RideHistoryRepository` | Manual backup | `rideHistoryRepo.backupToNostr()` |

---

## MainActivity Initialization Flow

```kotlin
// Line 131: NostrService with custom relays
val nostrService = NostrService(context, settingsManager.getEffectiveRelays())

// Line 153-154: WalletService initialization
val walletService = WalletService(context, walletKeyManager)

// Line 159-165: Nip60WalletSync wiring
val nip60Sync = Nip60WalletSync(...)
walletService.setNip60Sync(nip60Sync)

// Line 173-175: ProfileSyncManager singleton
val profileSyncManager = ProfileSyncManager.getInstance(context, relays)

// Line 182-186: Sync adapter registration
profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepo, nostrService))
profileSyncManager.registerSyncable(SavedLocationSyncAdapter(savedLocationRepo, nostrService))

// Line 268-272: On login, trigger sync
profileSyncManager.onKeyImported()
```

---

## Navigation Structure

```
Screen.ONBOARDING → (login/generate key) → Screen.MAIN_MAP
Screen.MAIN_MAP → (tap wallet card) → Screen.WALLET_DETAIL
Screen.MAIN_MAP → (tap history) → Screen.HISTORY
Screen.MAIN_MAP → (tap profile) → AccountBottomSheet
Screen.MAIN_MAP → (ride in progress) → DriverTrackingOverlay
Screen.RIDE_DETAIL → (from history) → Full ride details
```

---

## Important Implementation Notes

### Ride State History
- `riderStateHistory` is a synchronized list that accumulates actions
- **CRITICAL**: Call `clearRiderStateHistory()` when starting a new ride
- History actions: `location_reveal`, `pin_verify`, `preimage_share`

### Preimage Sharing
- `sharePreimageWithDriver()` publishes preimage via Kind 30181
- Called after PIN verification at pickup
- Driver uses preimage to claim HTLC payment

### Subscriptions
- `subscribeToDriverRideState()` - Watch driver's Kind 30180 events
- `subscribeToAcceptances()` - Watch for Kind 3174 responses
- `closeAllRideSubscriptions()` - Clean up on ride end

### Confirmation Flow Protection (January 2026)
Race condition fix prevents duplicate confirmation events:
- `autoConfirmRide()` sets `isConfirmingRide = true` **before** launching coroutine (line 1844)
- `confirmRide()` guards against `isConfirmingRide` and existing `confirmationEventId` (lines 1489-1498)
- Both paths reset `isConfirmingRide = false` on success or failure
- **Without this**: User could tap manual confirm during async auto-confirm, causing two Kind 3175 events with different IDs → rider/driver desync

### Auto-Confirm UI (January 2026)
- When driver accepts, rider UI shows ride summary with "Confirming ride..." spinner
- No manual confirm button - confirmation happens automatically via `autoConfirmRide()`
- Driver has 30-second timeout waiting for confirmation
- Only "Cancel Ride" button available during confirmation

### Cancel After PIN Verification (January 2026)
If rider cancels after preimage was shared with driver:
- `attemptCancelRide()` checks `preimageShared || pinVerified` (line 1547)
- Warning dialog shows that payment was already authorized
- Driver can still claim the fare even after cancellation
- `preimageShared` set to `true` after successful `sharePreimageWithDriver()` (line 343)

### Deposit/Withdraw
- Tap wallet card in `WalletScreen.kt` → navigates to `WalletDetailScreen` (common)
- Deposit and withdraw are **fully functional**
- Only LN address resolution is broken (must paste BOLT11 directly)
