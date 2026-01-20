# Driver App Module (Drivestr)

## Overview

The driver Android app allows users to go online, receive ride offers, navigate to pickups/dropoffs, and receive payments via Cashu wallet. All state is centralized in `DriverViewModel`, which coordinates between NostrService for communication and WalletService for payments.

---

## Key Components

### ViewModels (`java/com/drivestr/app/viewmodels/`)

| File | Purpose | Key State Fields |
|------|---------|------------------|
| `DriverViewModel.kt` | Central state machine (~2800 lines) | `driverStage`, `currentRide`, `currentOffer`, `driverStateHistory` |
| `OnboardingViewModel.kt` | Key generation/import flow | `uiState`, `isLoggedIn` |
| `ProfileViewModel.kt` | Profile editing | `name`, `profilePicture` |

### Screens (`java/com/drivestr/app/ui/screens/`)

| File | Purpose |
|------|---------|
| `DriverModeScreen.kt` | Main driver UI - map, offer cards, navigation overlay |
| `WalletScreen.kt` | Earnings display card - tap opens `WalletDetailScreen` |
| `EarningsScreen.kt` | Earnings history, stats, manual backup |
| `VehiclesScreen.kt` | Vehicle list with add/edit/delete |
| `VehicleSetupScreen.kt` | New vehicle form |
| `VehiclePickerDialog.kt` | Vehicle selection for going online |
| `SettingsScreen.kt` | App settings, relay config, developer options |
| `OnboardingScreen.kt` | Login/signup flow |
| `ProfileSetupScreen.kt` | Profile name and picture setup |
| `KeyBackupScreen.kt` | nsec backup display |
| `DebugScreen.kt` | Developer diagnostics |

### Services (`java/com/drivestr/app/service/`)

| File | Purpose |
|------|---------|
| `DriverOnlineService.kt` | Foreground service - keeps driver online, refreshes availability |

### Entry Point

| File | Purpose |
|------|---------|
| `MainActivity.kt` | App entry, Compose navigation, dependency injection, sync adapter registration |

---

## State Machine (DriverViewModel)

```
OFFLINE
  │
  ▼ goOnline() / broadcastAvailability()
AVAILABLE
  │
  ▼ acceptOffer() / acceptBroadcastRequest()
RIDE_ACCEPTED
  │
  ▼ startRouteToPickup()
EN_ROUTE_TO_PICKUP
  │
  ▼ arrivedAtPickup()
ARRIVED_AT_PICKUP
  │
  ▼ PIN verified, startRide()
IN_RIDE
  │
  ▼ arrivedAtDropoff()
RIDE_COMPLETED → finishAndGoOnline() → AVAILABLE

Any state → cancelCurrentRide() → CANCELLED → goOnline() → AVAILABLE
```

### Key State Transitions

| Method | From State | To State | Nostr Event |
|--------|------------|----------|-------------|
| `goOnline()` | OFFLINE | AVAILABLE | Kind 30173 |
| `acceptOffer()` | AVAILABLE | RIDE_ACCEPTED | Kind 3174 |
| `acceptBroadcastRequest()` | AVAILABLE | RIDE_ACCEPTED | Kind 3174 |
| `updateDriverStatus()` | Various | Various | Kind 30180 |
| `cancelCurrentRide()` | Any | CANCELLED | Kind 3179 |
| `clearDriverStateHistory()` | - | - | **CRITICAL**: Must call when starting new ride |

---

## Connections Table

| From (this module) | To (common module) | How/Why | Example Call |
|--------------------|-------------------|---------|--------------|
| `DriverViewModel` | `NostrService` | Broadcast availability | `nostrService.broadcastAvailability()` |
| `DriverViewModel` | `NostrService` | Accept ride offer | `nostrService.acceptRide()` |
| `DriverViewModel` | `NostrService` | Subscribe to offers | `nostrService.subscribeToOffers()` |
| `DriverViewModel` | `NostrService` | Publish driver state | `nostrService.publishDriverRideState()` |
| `DriverViewModel` | `WalletService` | Claim HTLC payment | `walletService.claimHtlcPayment()` |
| `DriverViewModel` | `WalletService` | Check wallet connected | `walletService.isConnected` |
| `DriverViewModel` | `RideHistoryRepository` | Save completed ride | `rideHistoryRepo.addRide()` |
| `DriverViewModel` | `VehicleRepository` | Get primary vehicle | `vehicleRepo.primaryVehicle` |
| `MainActivity` | `ProfileSyncManager` | Register sync adapters | `profileSyncManager.registerSyncable()` |
| `MainActivity` | `Nip60WalletSync` | Wire to WalletService | `walletService.setNip60Sync()` |
| `MainActivity` | `VehicleRepository` | Vehicle data for UI | `vehicleRepository.vehicles.value` |
| `WalletScreen` | `WalletService` | Display earnings | `walletService.balance.value` |
| `EarningsScreen` | `RideHistoryRepository` | Display past rides | `rideHistoryRepo.rides.value` |
| `VehiclesScreen` | `VehicleRepository` | CRUD operations | `vehicleRepo.addVehicle()` |

---

## MainActivity Initialization Flow

```kotlin
// Line 131: NostrService with custom relays
val nostrService = NostrService(context, settingsManager.getEffectiveRelays())

// Line 148-149: WalletService initialization
val walletService = WalletService(context, walletKeyManager)

// Line 152-157: Nip60WalletSync wiring
val nip60Sync = Nip60WalletSync(...)
walletService.setNip60Sync(nip60Sync)

// Line 171-173: ProfileSyncManager singleton
val profileSyncManager = ProfileSyncManager.getInstance(context, relays)

// Line 176-180: Sync adapter registration (driver-specific)
profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepository, nostrService))
profileSyncManager.registerSyncable(VehicleSyncAdapter(vehicleRepository, nostrService))

// Line 255-256: On login, trigger sync
profileSyncManager.onKeyImported()
```

---

## Navigation Structure

```
Screen.ONBOARDING → (login/generate key) → Screen.MAIN_MAP
Screen.MAIN_MAP → (tap wallet card) → Screen.WALLET_DETAIL
Screen.MAIN_MAP → (tap earnings) → Screen.EARNINGS
Screen.MAIN_MAP → (tap vehicles) → Screen.VEHICLES
Screen.MAIN_MAP → (tap profile) → AccountBottomSheet
Screen.VEHICLES → (add vehicle) → Screen.VEHICLE_SETUP
Screen.MAIN_MAP → (go online) → VehiclePickerDialog (if multiple vehicles)
```

---

## Important Implementation Notes

### Driver State History
- `driverStateHistory` is a synchronized list that accumulates actions
- **CRITICAL**: Call `clearDriverStateHistory()` in both:
  - `acceptOffer()` (line ~1029)
  - `acceptBroadcastRequest()` (line ~2798)
- History actions: `status`, `location_update`, `pin_submit`, `settlement`

### Phantom Cancellation Bug Prevention
The phantom cancellation bug was caused by not clearing history between rides.
- Old cancellation actions from ride #1 would appear in ride #2's events
- Fix: Always call `clearDriverStateHistory()` at the START of a new ride

### Payment Claiming
- `claimHtlcPayment()` called at line 1621 after ride completion
- Driver receives preimage from rider's Kind 30181 event
- **Note**: P2PK witness signatures not implemented - empty array passed

### Vehicle Selection
- Driver must have at least one vehicle to go online
- `VehiclePickerDialog` shown if multiple vehicles exist
- Primary vehicle selected by default

### Broadcast vs Direct Offers
| Pattern | Tag | Subscription | Use Case |
|---------|-----|--------------|----------|
| Broadcast | `g` (geohash) | `subscribeToBroadcastRideRequests()` | Open auction |
| Direct | `p` (driver pubkey) | `subscribeToOffers()` | Retry specific driver |

### Deposit/Withdraw
- Tap wallet card in `WalletScreen.kt` → navigates to `WalletDetailScreen` (common)
- Deposit and withdraw are **fully functional**
- Only LN address resolution is broken (must paste BOLT11 directly)
