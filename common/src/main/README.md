# Common Module

## Overview

The `common` module contains all shared code used by both rider and driver apps: payment processing (Cashu/HTLC), Nostr event handling, profile sync, data repositories, routing services, and shared UI components.

---

## Key Components

### Payment System (`java/com/ridestr/common/payment/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `WalletService.kt` | Orchestration layer (⚠️ safe deletion pattern required - see cashu-wallet skill) | `syncWallet()`, `requestDeposit()`, `checkDepositStatus()`, `getMeltQuote()`, `executeWithdraw()`, `lockForRide()`, `claimHtlcPayment()`, `checkAndRefundExpiredHtlcs()`, `changeMintUrl()` |
| `cashu/CashuBackend.kt` | Mint operations (NUT-04/05) | `getMintQuote()`, `mintTokens()`, `getMeltQuote()`, `meltTokens()`, `createHtlcTokenFromProofs()` |
| `cashu/Nip60WalletSync.kt` | Cross-device wallet sync (NIP-60 compliant) | `publishProofs()`, `publishWalletMetadata()`, `restoreFromNostr()`, `hasExistingWallet()` |
| `WalletKeyManager.kt` | Wallet keypair + signing | `getPrivateKeyBytes()`, `signSchnorr()`, `getWalletPubKeyHex()`, `importPrivateKey()`, `importMnemonic()` |
| `WalletStorage.kt` | Local persistence | `savePendingDeposit()`, `getCachedBalance()`, `saveMintUrl()` |
| `PaymentCrypto.kt` | Preimage/hash generation | `generatePreimage()`, `hashPreimage()` |
| `PaymentModels.kt` | Data classes | `MintQuote`, `MeltQuote`, `PaymentTransaction`, `EscrowLock` |

### Nostr Layer (`java/com/ridestr/common/nostr/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `NostrService.kt` | Event publishing/subscription | `broadcastAvailability()`, `publishDriverRideState()`, `publishRiderRideState()`, `subscribeToOffers()`, `publishRideHistoryBackup()` |
| `relay/RelayManager.kt` | WebSocket connection pool | `connectAll()`, `publish()`, `subscribe()`, `unsubscribe()` |
| `relay/RelayConnection.kt` | Single relay connection | WebSocket lifecycle management |
| `keys/KeyManager.kt` | Nostr identity | `getSigner()`, `getPubKeyHex()`, `refreshFromStorage()`, `generateNewKey()` |
| `keys/SecureKeyStorage.kt` | Encrypted key storage | EncryptedSharedPreferences wrapper |

### Event Models (`java/com/ridestr/common/nostr/events/`)

| File | Kind | Purpose |
|------|------|---------|
| `DriverAvailabilityEvent.kt` | 30173 | Driver broadcasts availability with geohash |
| `RideOfferEvent.kt` | 3173 | Rider sends offer to driver (NIP-44 encrypted) |
| `RideAcceptanceEvent.kt` | 3174 | Driver accepts ride offer (includes `wallet_pubkey`) |
| `RideConfirmationEvent.kt` | 3175 | Rider confirms with PIN |
| `DriverRideStateEvent.kt` | 30180 | Driver status updates (history-based) |
| `RiderRideStateEvent.kt` | 30181 | Rider location/preimage sharing (history-based) |
| `RideshareChatEvent.kt` | 3178 | In-ride encrypted chat |
| `RideCancellationEvent.kt` | 3179 | Ride cancellation |
| `RideHistoryEvent.kt` | 30174 | Ride history backup (encrypted to self) |
| `VehicleBackupEvent.kt` | 30175 | Vehicle list backup (driver) |
| `SavedLocationBackupEvent.kt` | 30176 | Saved locations backup (rider) |
| `RideshareEventKinds.kt` | - | Kind constants and expiration times |

### Sync System (`java/com/ridestr/common/sync/`)

| File | Purpose | Sync Order |
|------|---------|------------|
| `ProfileSyncManager.kt` | Central orchestrator | Manages all adapters, coordinates restore |
| `SyncableProfileData.kt` | Interface | Contract for sync adapters |
| `ProfileSyncState.kt` | Observable state | UI feedback for sync progress |
| `Nip60WalletSyncAdapter.kt` | Wallet sync | **0** (highest priority) |
| `RideHistorySyncAdapter.kt` | History sync | **1** |
| `VehicleSyncAdapter.kt` | Vehicle sync (driver) | **2** |
| `SavedLocationSyncAdapter.kt` | Location sync (rider) | **3** |

### Data Repositories (`java/com/ridestr/common/data/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `RideHistoryRepository.kt` | Ride history storage | `addRide()`, `syncFromNostr()`, `backupToNostr()` |
| `VehicleRepository.kt` | Driver vehicles | `addVehicle()`, `updateVehicle()`, `setPrimaryVehicle()` |
| `SavedLocationRepository.kt` | Rider saved locations | `addRecent()`, `pinAsFavorite()`, `restoreFromBackup()` |

### Routing (`java/com/ridestr/common/routing/`)

| File | Purpose |
|------|---------|
| `ValhallaRoutingService.kt` | Turn-by-turn directions via Valhalla API |
| `TileManager.kt` | Offline map tile management |
| `TileDownloadService.kt` | Background tile downloads |
| `BlossomTileService.kt` | Blossom protocol tile discovery |
| `NostrTileDiscoveryService.kt` | Nostr-based tile source discovery |

### Shared UI (`java/com/ridestr/common/ui/`)

| File | Purpose |
|------|---------|
| `WalletDetailScreen.kt` | Deposit/Withdraw dialogs (**FULLY WORKING**) |
| `WalletSettingsScreen.kt` | Wallet management (sync, change mint, diagnostics) |
| `WalletSetupScreen.kt` | Mint connection setup |
| `AccountBottomSheet.kt` | Profile, logout, account safety |
| `ChatBottomSheet.kt` | In-ride messaging UI |
| `RelayManagementScreen.kt` | Custom relay configuration |
| `RideDetailScreen.kt` | Ride history detail view |
| `SlideToConfirm.kt` | Swipe confirmation widget |
| `FareDisplay.kt` | Fare breakdown display |

### Other Services

| File | Purpose |
|------|---------|
| `bitcoin/BitcoinPriceService.kt` | BTC/USD price fetching |
| `location/GeocodingService.kt` | Address search/reverse geocoding |
| `notification/NotificationHelper.kt` | Push notification management |
| `notification/SoundManager.kt` | Sound effects for ride events |
| `settings/SettingsManager.kt` | App settings persistence |

---

## Connections Table

| From (this module) | To (other module/external) | How/Why | Example Call |
|--------------------|---------------------------|---------|--------------|
| `WalletService` | `CashuBackend` | Mint operations | `cashuBackend.getMintQuote(sats)` |
| `WalletService` | `Nip60WalletSync` | Cross-device sync | `nip60Sync.publishProofs()` |
| `WalletService` | `WalletStorage` | Persistence | `walletStorage.savePendingDeposit()` |
| `NostrService` | `RelayManager` | Event publishing | `relayManager.publish(event)` |
| `NostrService` | `KeyManager` | Event signing | `keyManager.getSigner()` |
| `CashuBackend` | Cashu Mint (HTTP) | Token operations | `POST /v1/mint/quote` |
| `Nip60WalletSync` | `NostrService` | Proof publishing | Kind 7375 events |
| `ProfileSyncManager` | `KeyManager` | Shared identity | `keyManager.refreshFromStorage()` |
| `ProfileSyncManager` | `RelayManager` | Relay connections | `relayManager.connectAll()` |
| `ProfileSyncManager` | All SyncAdapters | Coordinated restore | `syncable.fetchFromNostr()` |
| `RideHistorySyncAdapter` | `RideHistoryRepository` | Data source | `repo.syncFromNostr()` |
| `RideHistorySyncAdapter` | `NostrService` | Backup publishing | `nostrService.publishRideHistoryBackup()` |
| `VehicleSyncAdapter` | `VehicleRepository` | Data source | `repo.restoreFromBackup()` |
| `VehicleSyncAdapter` | `NostrService` | Backup publishing | `nostrService.backupVehicles()` |
| `SavedLocationSyncAdapter` | `SavedLocationRepository` | Data source | `repo.restoreFromBackup()` |
| `SavedLocationSyncAdapter` | `NostrService` | Backup publishing | `nostrService.backupSavedLocations()` |
| `WalletDetailScreen` | `WalletService` | Deposit/Withdraw | `walletService.requestDeposit()` |
| `RelayManager` | Nostr Relays (WebSocket) | Event transport | WebSocket connections |

---

## Important Implementation Notes

### Deposit Flow (Working)
```
WalletDetailScreen.kt:444-682
  → Button "Deposit" → showDepositDialog
  → User enters sats amount
  → walletService.requestDeposit(sats)
  → Poll: walletService.checkDepositStatus(quoteId)
  → On success: tokens minted + synced to NIP-60
```

### Withdraw Flow (Working)
```
WalletDetailScreen.kt:688-880
  → Button "Withdraw" → showWithdrawDialog
  → User enters Lightning invoice (or LN address)
  → walletService.getMeltQuote(bolt11)
  → walletService.executeWithdraw(quote)
  → Tokens melted, Lightning paid
```

### LN Address Resolution (BROKEN)
- `WalletService.resolveLnAddress()` at line 600-604 does not work
- Users must paste BOLT11 invoice directly instead of using user@domain.com format

### HTLC Implementation (COMPLETE)
- `CashuBackend.createHtlcTokenFromProofs()` creates NUT-14 structure
- `WalletKeyManager.signSchnorr()` creates BIP-340 Schnorr signatures
- `CashuBackend.signP2pkProof()` creates per-proof witness signatures
- `CashuBackend.claimHtlcToken()` includes proper P2PK signatures

### Critical: Wallet Pubkey Handshake
Driver's **wallet key** (for P2PK) is different from **Nostr key** (for identity).
- Driver sends `wallet_pubkey` in acceptance event
- Rider uses `acceptance.walletPubKey` for HTLC P2PK condition
- HTLC locked AFTER acceptance (in `autoConfirmRide()`), not before

### NUT-07 Stale Proof Verification
Before HTLC swap, proofs are verified with mint to catch stale NIP-60 events:
- `WalletService.lockForRide()` calls `verifyProofStatesBySecret()` at line 324
- If any proofs are SPENT → deletes their NIP-60 events → retries selection
- Prevents "Token already spent" (code 11001) errors from stale proofs

### HTLC Refund Flow
- `WalletService.checkAndRefundExpiredHtlcs()` runs on wallet `connect()` to auto-refund expired HTLCs
- `findHtlcByPaymentHash()` and `markHtlcClaimedByPaymentHash()` track HTLC lifecycle
- `RiderViewModel.handleRideCompletion()` marks HTLC as claimed to prevent false refund attempts

### Wallet Sync Architecture
- **NIP-60 IS the wallet** (source of truth), cdk-kotlin is only for mint API calls
- `WalletService.syncWallet()` - THE sync function that handles everything:
  - Fetches ALL NIP-60 proofs (regardless of stored mint URL)
  - Verifies at current mint first, then tries other mints if needed
  - Auto-migrates proof URLs when mint URL changes
  - Cleans up spent proofs from NIP-60
  - Updates displayed balance
- `changeMintUrl()` uses `syncWallet()` internally for verification + migration + metadata update
- UI in `WalletSettingsScreen.kt` (Settings → Wallet → Sync Wallet)
- Developer Options only contains "Always Show Diagnostics" toggle for wallet

### NIP-60 Compliance (January 2026)
Both wallet event types are fully NIP-60 compliant:
- **Kind 7375 (Proofs)**: `{"mint":"...","proofs":[...]}` - JSON object format
- **Kind 17375 (Metadata)**: `[["privkey","..."],["mint","..."]]` - Array of tag-like pairs
- Metadata includes `privkey` (wallet key) and `mnemonic` (cdk-kotlin seed) for cross-device restore
- `restoreFromNostr()` reads both NIP-60 array and legacy JSON formats (backwards compatible)

### Profile Sync Order
When user imports existing key, sync happens in this order:
1. **Wallet** (order=0) - Highest priority, needed for payments
2. **Ride History** (order=1) - May reference payments
3. **Vehicles** (order=2, driver only) - Profile data
4. **Saved Locations** (order=3, rider only) - Convenience data
