# Common Module

## Overview

The `common` module contains all shared code used by both rider and driver apps: payment processing (Cashu/HTLC), Nostr event handling, profile sync, data repositories, routing services, and shared UI components.

---

## Key Components

### Payment System (`java/com/ridestr/common/payment/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `WalletService.kt` | Orchestration layer (⚠️ safe deletion pattern required - see cashu-wallet skill) | `syncWallet()`, `requestDeposit()`, `checkDepositStatus()`, `getMeltQuote()`, `executeWithdraw()`, `lockForRide()`, `claimHtlcPayment()`, `mintTokens()`, `claimUnclaimedDeposits()`, `changeMintUrl()`, `recoverPendingOperations()`, `bridgePayment()`, `recoverFromSeed()` |
| `cashu/CashuBackend.kt` | Mint operations (NUT-04/05/14/17), NUT-13 deterministic secrets, WebSocket state updates | `getMintQuote()`, `mintTokens()`, `getMeltQuote()`, `meltWithProofs()`, `createHtlcTokenFromProofs()`, `claimHtlcTokenWithProofs()`, `refundExpiredHtlc()`, `restoreProofs()`, `getActiveKeysetIds()`, `waitForMeltQuoteState()`, `waitForMintQuoteState()` |
| `cashu/CashuWebSocket.kt` | NUT-17 WebSocket connection for real-time mint state updates | `connect()`, `disconnect()`, `subscribe()`, `unsubscribe()`, `isConnected()` |
| `cashu/CashuWebSocketModels.kt` | NUT-17 JSON-RPC 2.0 data classes | `WsRequest`, `WsResponse`, `WsNotification`, `MintQuotePayload`, `MeltQuotePayload`, `SubscriptionKind` |
| `cashu/CashuCrypto.kt` | Cryptographic operations (NUT-00/13) | `hashToCurve()`, `blindMessage()`, `unblindSignature()`, `mnemonicToSeed()`, `deriveSecrets()`, `derivePreMintSecret()` |
| `cashu/Nip60WalletSync.kt` | Cross-device wallet sync (NIP-60, EOSE-aware queries) | `publishProofs()`, `fetchProofs()`, `publishWalletMetadata()`, `restoreFromNostr()`, `hasExistingWallet()` (counter backup, EOSE early-exit) |
| `WalletKeyManager.kt` | Wallet keypair + signing | `getPrivateKeyBytes()`, `signSchnorr()`, `getWalletPubKeyHex()`, `importPrivateKey()`, `importMnemonic()` |
| `WalletStorage.kt` | Local persistence + NUT-13 counters | `savePendingDeposit()`, `getPendingDeposits()`, `removePendingDeposit()`, `getCachedBalance()`, `saveMintUrl()`, `savePendingBlindedOp()`, `getRecoverableBlindedOps()`, `savePendingHtlc()`, `getRefundableHtlcs()`, `getCounter()`, `incrementCounter()`, `getAllCounters()` |
| `PaymentCrypto.kt` | Preimage/hash generation | `generatePreimage()`, `hashPreimage()` |
| `PaymentModels.kt` | Data classes | `MintQuote`, `MeltQuote`, `PaymentTransaction`, `EscrowLock`, `PendingDeposit`, `ClaimResult`, `WalletBalance`, `PendingHtlc`, `PendingBlindedOperation`, `SeedRecoveryResult` |

### Nostr Layer (`java/com/ridestr/common/nostr/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `NostrService.kt` | Event publishing/subscription | `broadcastAvailability()`, `publishDriverRideState()`, `publishRiderRideState()`, `subscribeToOffers()`, `subscribeToDriverAvailability()`, `publishRideHistoryBackup()` |
| `relay/RelayManager.kt` | WebSocket connection pool, EOSE-aware subscriptions | `connectAll()`, `publish()`, `subscribe(onEose=...)`, `closeSubscription()` |
| `relay/RelayConnection.kt` | Single relay connection | WebSocket lifecycle management |
| `relay/RelayConfig.kt` | Configuration constants | Default relays, timeouts |
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
| `ProfileBackupEvent.kt` | 30177 | **Unified profile backup** (vehicles, locations, settings) |
| `AdminConfigEvent.kt` | 30182 | Platform config (fare rates, mints, versions) - from admin pubkey |
| `RideshareEventKinds.kt` | - | Kind constants, expiration times, `PaymentMethod` enum |

### Sync System (`java/com/ridestr/common/sync/`)

| File | Purpose | Sync Order |
|------|---------|------------|
| `ProfileSyncManager.kt` | Central orchestrator | Manages all adapters, coordinates restore + auto-backup |
| `SyncableProfileData.kt` | Interface | Contract for sync adapters |
| `ProfileSyncState.kt` | Observable state | UI feedback for sync progress |
| `Nip60WalletSyncAdapter.kt` | Wallet sync | **0** (highest priority) |
| `ProfileSyncAdapter.kt` | **Unified profile sync** (vehicles, locations, settings) | **1** |
| `RideHistorySyncAdapter.kt` | History sync | **2** |

### State Machine (`java/com/ridestr/common/state/`)

Formal state machine implementation following the AtoB pattern with named guards and actions.

| File | Purpose | Key Components |
|------|---------|----------------|
| `RideState.kt` | Unified state enum | `CREATED`, `ACCEPTED`, `CONFIRMED`, `EN_ROUTE`, `ARRIVED`, `IN_PROGRESS`, `COMPLETED`, `CANCELLED` |
| `RideEvent.kt` | Event types sealed class | `Accept`, `Confirm`, `StartRoute`, `Arrive`, `VerifyPin`, `StartRide`, `Complete`, `Cancel`, etc. |
| `RideContext.kt` | Guard/action evaluation context | Participant identity, ride IDs, location data, payment data, PIN state |
| `RideTransition.kt` | Transition table (16 transitions) | `from`, `eventType`, `to`, `guard`, `action` |
| `RideGuards.kt` | Named guard functions | `isRider`, `isDriver`, `isPinVerified`, `hasEscrowLocked`, `canSettle`, etc. |
| `RideActions.kt` | Named action functions | `assignDriver`, `lockEscrow`, `startRideAfterPin`, `settlePayment` |
| `RideStateMachine.kt` | Main processor | `processEvent()`, `canTransition()`, `availableEvents()` |

**State Flow:**
```
CREATED → ACCEPTED → CONFIRMED → EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED
   ↓         ↓          ↓           ↓          ↓           ↓
   └─────────┴──────────┴───────────┴──────────┴───────────┴──→ CANCELLED
```

**Integration (Phase 1 - Validation Only):**
- ViewModels create `RideContext` and call `validateTransition()` before state changes
- Invalid transitions logged as warnings but don't block existing behavior
- Guards evaluate authorization (e.g., only rider can confirm, only driver can complete)

### Data Repositories (`java/com/ridestr/common/data/`)

| File | Purpose | Key Methods |
|------|---------|-------------|
| `RideHistoryRepository.kt` | Ride history storage (with grace period protection) | `addRide()`, `syncFromNostr()`, `backupToNostr()`, `clearAllHistoryAndDeleteFromNostr()` |
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
| `AccountSafetyScreen.kt` | Key backup, account recovery options |
| `ChatBottomSheet.kt` | In-ride messaging UI |
| `ChatView.kt` | Chat message rendering component |
| `DeveloperOptionsScreen.kt` | Debug toggles (geocoding, manual location) + link to Relay Settings |
| `FareDisplay.kt` | Fare breakdown display |
| `LocationPermissionScreen.kt` | Location permission request flow |
| `LocationSearchField.kt` | Address search input with geocoding |
| `ProfilePictureEditor.kt` | Profile image editing |
| `ProfileSyncScreen.kt` | Onboarding sync UI for existing key import |
| `RelayManagementScreen.kt` | Custom relay configuration, per-relay status dots, reconnect button |
| `RelaySignalIndicator.kt` | Relay connection status indicator |
| `RideDetailScreen.kt` | Ride history detail view |
| `SavedLocationComponents.kt` | Saved location chips and lists |
| `SlideToConfirm.kt` | Swipe confirmation widget |
| `TileManagementScreen.kt` | Offline map tile management |
| `TileSetupScreen.kt` | Tile download region setup |

### Other Services

| File | Purpose |
|------|---------|
| `bitcoin/BitcoinPriceService.kt` | BTC/USD price fetching |
| `location/GeocodingService.kt` | Address search/reverse geocoding |
| `notification/NotificationHelper.kt` | Push notification management |
| `notification/SoundManager.kt` | Sound effects for ride events |
| `settings/SettingsManager.kt` | App settings persistence + `syncableSettingsHash` for auto-backup |
| `settings/RemoteConfigManager.kt` | Platform config from admin pubkey (fare rates, mints) - one-time fetch on startup |

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
| `ProfileSyncAdapter` | `VehicleRepository` | Vehicle data (driver) | `repo.restoreFromBackup()` |
| `ProfileSyncAdapter` | `SavedLocationRepository` | Location data (rider) | `repo.restoreFromBackup()` |
| `ProfileSyncAdapter` | `SettingsManager` | Settings sync | `settingsManager.restoreFromBackup()` |
| `ProfileSyncAdapter` | `NostrService` | Unified backup (Kind 30177) | `nostrService.backupProfile()` |
| `SettingsManager` | MainActivity | Auto-backup observer | `syncableSettingsHash` Flow triggers backup |
| `WalletDetailScreen` | `WalletService` | Deposit/Withdraw | `walletService.requestDeposit()` |
| `RelayManager` | Nostr Relays (WebSocket) | Event transport | WebSocket connections |
| `RideStateMachine` | `DriverViewModel` | Transition validation | `stateMachine.processEvent(event, state, context)` |
| `RideStateMachine` | `RiderViewModel` | Transition validation | `stateMachine.canTransition(state, "CONFIRM", context)` |
| `RideGuards` | `RideContext` | Authorization evaluation | `Guards.isRider(context)` |
| `RideActions` | `ActionHandler` | Side effect execution | `handler.assignDriver(context, event)` |
| `RemoteConfigManager` | `RelayManager` | Fetch admin config | `relayManager.subscribe(kind=30182, ...)` |
| `RemoteConfigManager` | `AdminConfigEvent` | Parse config event | `AdminConfigEvent.parse(event)` |

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
- `markHtlcClaimedByPaymentHash()` also clears `pendingSats` from balance (January 2026)
- `PendingHtlc` stores `htlcToken` for refund even after ride ends - only cleaned up after 7 days

### Automatic Wallet Refresh (January 2026)
All major payment operations now trigger automatic wallet refresh to ensure balance consistency:

| Operation | Location | Refresh Method |
|-----------|----------|----------------|
| HTLC Claim (driver) | `WalletService.claimHtlcPayment()` | NIP-60 fetch + `updateDiagnostics()` |
| Ride Completion (rider) | `RiderViewModel.handleRideCompletion()` | `refreshBalance()` |
| Ride Cancellation (rider) | `RiderViewModel.handleDriverCancellation()` | `refreshBalance()` |
| Withdrawal | `WalletService.executeWithdraw()` | NIP-60 fetch via `meltWithProofs()` |
| Cross-Mint Bridge | `WalletService.bridgePayment()` | NIP-60 fetch |

This ensures:
- Displayed balance matches NIP-60 (green diagnostics icon)
- `pendingSats` is cleared when HTLC is claimed
- Expired HTLCs are checked for refund on cancellation

### Ride History Clear Grace Period (January 2026)
When user clears ride history, a 30-second grace period prevents sync from restoring deleted data:
- `RideHistoryRepository.clearAllHistory()` sets `lastClearedAt` timestamp
- `syncFromNostr()` skips restore if within `CLEAR_GRACE_PERIOD_MS` (30 seconds)
- This allows NIP-09 deletion events to propagate to relays before next sync

### Stale Deposit Cleanup (January 2026)
Pending deposits are automatically cleaned up when their mint quote no longer exists:
- `claimUnclaimedDeposits()` removes deposits when quote not found at mint
- `syncWallet()` checks for and removes stale deposits during balance sync
- Prevents orphaned "Pending deposit" UI indicators

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

### Pending Blinded Operations Safety (January 2026)

All mint operations that create blinded outputs now follow a safe pattern:

1. **Save premints BEFORE request** - Blinding factors (r values) saved to `PendingBlindedOperation`
2. **Execute mint operation** - Send request to mint
3. **Mark as COMPLETED** - Operation succeeded, proofs received
4. **Persist to NIP-60** - Publish proofs with retry + RecoveryToken fallback
5. **Clear pending op** - ONLY after NIP-60 persist (or RecoveryToken saved)

**Covered operations:**
- `lockForRide()` → `createHtlcTokenFromProofs()` (HTLC escrow lock)
- `claimHtlcPayment()` → `claimHtlcTokenWithProofs()` (driver claims payment)
- `refundExpiredHtlcs()` → `refundExpiredHtlc()` (rider refunds expired HTLC)
- `executeWithdraw()` → `meltWithProofs()` (Lightning withdrawal)
- `bridgePayment()` → `meltWithProofs()` (cross-mint payment)

**Recovery on restart:**
- `WalletService.connect()` calls `recoverPendingOperations()`
- Checks for STARTED/PENDING operations, verifies inputs with mint
- If inputs SPENT → outputs exist at mint → unblind and recover

**Balance tracking:**
- `pendingSats` tracks funds locked in active HTLCs
- `lockForRide()` accumulates (adds) to pendingSats
- `recalculatePendingSats()` recalculates from active HTLCs on `connect()`

### NIP-60 Compliance (January 2026)
Both wallet event types are fully NIP-60 compliant:
- **Kind 7375 (Proofs)**: `{"mint":"...","proofs":[...]}` - JSON object format
- **Kind 17375 (Metadata)**: `[["privkey","..."],["mint","..."]]` - Array of tag-like pairs
- Metadata includes `privkey` (wallet key) and `mnemonic` (cdk-kotlin seed) for cross-device restore
- `restoreFromNostr()` reads both NIP-60 array and legacy JSON formats (backwards compatible)

### Profile Sync Order
When user imports existing key, sync happens in this order:
1. **Wallet** (order=0) - Highest priority, needed for payments
2. **Profile** (order=1) - Unified: vehicles, saved locations, settings (Kind 30177)
3. **Ride History** (order=2) - May reference payments

### Auto-Backup Observer Pattern (January 2026)
Both apps automatically backup profile data when it changes:

**MainActivity observers:**
- `vehicles` → Watches `VehicleRepository.vehicles` (driver app)
- `savedLocations` → Watches `SavedLocationRepository.savedLocations` (rider app)
- `settingsHash` → Watches `SettingsManager.syncableSettingsHash` (both apps)

**Pattern:**
```kotlin
val settingsHash by settingsManager.syncableSettingsHash.collectAsState(initial = 0)
LaunchedEffect(settingsHash, uiState.isLoggedIn) {
    if (!uiState.isLoggedIn) return@LaunchedEffect
    kotlinx.coroutines.delay(2000) // Debounce
    profileSyncManager.backupProfileData()
}
```

**`syncableSettingsHash`** combines all synced settings into a single hash:
- Display currency, distance unit, notification preferences
- Auto-open navigation, always-ask-vehicle
- Payment methods, default payment method, mint URL
- Custom relays

When ANY setting changes, the hash changes, triggering auto-backup to Nostr.

### Multi-Mint Support (Issue #13 - Phase 1)
Protocol events now include payment method fields for multi-mint compatibility:
- `PaymentMethod` enum: `CASHU`, `LIGHTNING`, `FIAT_CASH` in `RideshareEventKinds.kt`
- `mint_url` and `payment_methods` in Driver Availability (Kind 30173)
- `mint_url` and `payment_method` in Ride Offer (Kind 3173) and Acceptance (Kind 3174)
- `paymentMethods`, `defaultPaymentMethod`, `mintUrl` in SettingsBackup (Kind 30177)
