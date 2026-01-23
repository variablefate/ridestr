# Ridestr Module Connections

**Last Updated**: 2026-01-22

This document provides a comprehensive view of how all modules connect in the Ridestr codebase. Use this as a reference when making changes to understand what might be affected.

---

## High-Level Architecture

```mermaid
graph TB
    subgraph "Rider App"
        RVM[RiderViewModel]
        RMS[RiderModeScreen]
        RWS[WalletScreen]
    end

    subgraph "Driver App"
        DVM[DriverViewModel]
        DMS[DriverModeScreen]
        DWS[WalletScreen]
    end

    subgraph "Common Module"
        NS[NostrService]
        WS[WalletService]
        PSM[ProfileSyncManager]

        subgraph "Payment"
            CB[CashuBackend]
            N60[Nip60WalletSync]
        end

        subgraph "Data"
            RHR[RideHistoryRepository]
            VR[VehicleRepository]
            SLR[SavedLocationRepository]
        end

        subgraph "Nostr"
            RM[RelayManager]
            KM[KeyManager]
        end
    end

    subgraph "External Services"
        MINT[Cashu Mint]
        RELAYS[Nostr Relays]
    end

    RVM --> NS
    RVM --> WS
    DVM --> NS
    DVM --> WS

    NS --> RM
    NS --> KM
    RM --> RELAYS

    WS --> CB
    WS --> N60
    CB --> MINT
    N60 --> NS

    PSM --> NS
    PSM --> RHR
    PSM --> VR
    PSM --> SLR
```

---

## Deposit Flow (Working)

```mermaid
sequenceDiagram
    participant U as User
    participant WDS as WalletDetailScreen
    participant WS as WalletService
    participant CB as CashuBackend
    participant M as Cashu Mint
    participant N60 as Nip60WalletSync
    participant NS as NostrService

    U->>WDS: Tap "Deposit"
    WDS->>WDS: Show DepositDialog
    U->>WDS: Enter amount (sats)
    WDS->>WS: requestDeposit(sats)
    WS->>CB: getMintQuote(sats)
    CB->>M: POST /v1/mint/quote/bolt11
    M-->>CB: {request: "lnbc...", quote: "..."}
    CB-->>WS: MintQuote
    WS->>WS: savePendingDeposit()
    WS-->>WDS: Show QR code

    U->>U: Pay Lightning invoice externally

    loop Poll every 5 seconds
        WDS->>WS: checkDepositStatus(quoteId)
        WS->>CB: checkMintQuote(quoteId)
        CB->>M: GET /v1/mint/quote/bolt11/{id}
        M-->>CB: {paid: true/false}
    end

    M-->>CB: {paid: true}
    CB->>M: POST /v1/mint/bolt11 (mint tokens)
    M-->>CB: Cashu proofs
    CB-->>WS: tokens minted
    WS->>N60: publishProofs()
    N60->>NS: Create Kind 7375 event
    NS-->>N60: Event published
    WS-->>WDS: Deposit complete
    WDS->>U: Show success
```

---

## Withdraw Flow (Working)

```mermaid
sequenceDiagram
    participant U as User
    participant WDS as WalletDetailScreen
    participant WS as WalletService
    participant CB as CashuBackend
    participant M as Cashu Mint

    U->>WDS: Tap "Withdraw"
    WDS->>WDS: Show WithdrawDialog
    U->>WDS: Enter Lightning invoice (bolt11)
    WDS->>WS: getMeltQuote(bolt11)
    WS->>CB: getMeltQuote(bolt11)
    CB->>M: POST /v1/melt/quote/bolt11
    M-->>CB: {quote, amount, fee_reserve}
    CB-->>WS: MeltQuote
    WS-->>WDS: Show fee breakdown

    U->>WDS: Confirm withdrawal
    WDS->>WS: executeWithdraw(quote)
    WS->>CB: meltTokens(quoteId)
    CB->>M: POST /v1/melt/bolt11
    M-->>CB: {paid: true, change: [...]}
    CB-->>WS: Melt complete
    WS-->>WDS: Success
    WDS->>U: Show confirmation
```

---

## Ride Payment Flow (HTLC Escrow)

```mermaid
sequenceDiagram
    participant R as Rider
    participant RVM as RiderViewModel
    participant WS as WalletService
    participant CB as CashuBackend
    participant MINT as Cashu Mint
    participant D as Driver
    participant DVM as DriverViewModel

    R->>RVM: Request ride
    RVM->>RVM: generatePreimage()
    RVM->>D: Kind 3173 (offer with paymentHash)
    D->>DVM: Receive offer
    DVM->>R: Kind 3174 (acceptance with wallet_pubkey)

    Note over RVM: HTLC locked AFTER acceptance

    RVM->>WS: lockForRide(fareSats, paymentHash, walletPubKey)
    WS->>WS: selectProofsForSpending()
    WS->>CB: verifyProofStatesBySecret() [NUT-07]
    CB->>MINT: POST /v1/checkstate
    MINT-->>CB: [UNSPENT/SPENT states]
    alt If stale proofs found
        WS->>WS: deleteProofEvents() + retry
    end
    WS->>CB: createHtlcTokenFromProofs()
    CB->>MINT: POST /v1/swap
    MINT-->>CB: HTLC proofs
    CB-->>WS: HTLC token
    WS-->>RVM: EscrowLock created

    R->>RVM: Confirm ride
    RVM->>D: Kind 3175 (confirmation with PIN)

    Note over R,D: Driver navigates to pickup

    D->>DVM: Arrive at pickup
    DVM->>R: Kind 30180 (PIN_SUBMIT)
    R->>RVM: Verify PIN
    RVM->>D: Kind 30181 (PIN_VERIFY + preimage)

    Note over R,D: Ride in progress

    D->>DVM: Complete ride
    DVM->>WS: claimHtlcPayment(preimage)
    WS->>CB: claimHtlcToken() with P2PK signature
    CB->>MINT: POST /v1/swap
    MINT-->>CB: Plain proofs
    WS-->>DVM: Payment claimed
```

---

## Cross-Mint Bridge Payment Flow (Multi-Mint)

When rider and driver use different Cashu mints, payment happens via Lightning bridge:

```mermaid
sequenceDiagram
    participant R as Rider
    participant RVM as RiderViewModel
    participant RWS as Rider WalletService
    participant RM as Rider Mint
    participant DM as Driver Mint
    participant DWS as Driver WalletService
    participant DVM as DriverViewModel
    participant D as Driver

    Note over R,D: Driver accepts ride (different mint detected)

    D->>DVM: Arrive at pickup
    DVM->>DWS: getDepositInvoice(fareSats)
    DWS->>DM: POST /v1/mint/quote/bolt11
    DM-->>DWS: {quote, request: "lnbc..."}
    DWS->>DWS: savePendingDeposit(quoteId)
    DVM->>R: Kind 30180 (DEPOSIT_INVOICE_SHARE)

    R->>RVM: Receive deposit invoice
    RVM->>RWS: bridgePayment(invoice)
    RWS->>RM: POST /v1/melt/quote/bolt11
    RM-->>RWS: {quote, fee_reserve}
    RWS->>RWS: selectProofsForSpending(total)
    RWS->>RM: POST /v1/melt/bolt11 (pay invoice)
    RM-->>RWS: {paid: true, preimage}
    RVM->>D: Kind 30181 (BRIDGE_COMPLETE + preimage)

    D->>DVM: Receive bridge complete
    DVM->>DWS: mintTokens(quoteId, amount)
    DWS->>DM: POST /v1/mint/bolt11
    DM-->>DWS: Cashu proofs
    DWS->>DWS: publishProofsToNip60()
    DWS->>DWS: refreshBalance()
    DVM->>DVM: crossMintPaymentComplete = true

    Note over R,D: Ride proceeds, no HTLC claim needed
```

**Key Points:**
- `shareDepositInvoice()` saves quote to `WalletStorage` for recovery
- `handleBridgeComplete()` calls `mintTokens()` to claim proofs
- `crossMintPaymentComplete` flag prevents MISSING_ESCROW_TOKEN error at dropoff
- Unclaimed deposits persist in local storage until claimed or manually cleared
- Developer Options → "Claim Unclaimed Deposits" for manual recovery

---

## Profile Sync Flow (Key Import)

```mermaid
sequenceDiagram
    participant U as User
    participant MA as MainActivity
    participant PSM as ProfileSyncManager
    participant KM as KeyManager
    participant RM as RelayManager
    participant SA1 as WalletSyncAdapter
    participant SA2 as ProfileSyncAdapter
    participant SA3 as HistorySyncAdapter

    U->>MA: Import nsec key
    MA->>KM: Store key
    MA->>PSM: onKeyImported()

    PSM->>RM: connectAll()
    RM-->>PSM: Connected

    Note over PSM: Sync in order (0, 1, 2)

    PSM->>SA1: fetchFromNostr() [order=0]
    SA1-->>PSM: Wallet restored (N proofs)

    PSM->>SA2: fetchFromNostr() [order=1]
    SA2-->>PSM: Profile restored (vehicles/locations + settings)

    PSM->>SA3: fetchFromNostr() [order=2]
    SA3-->>PSM: History restored (N rides)

    PSM-->>MA: Sync complete
```

---

## Cross-Module Dependencies

### Payment System

```
Payment System
├── WalletService (orchestration layer)
│   ├── Depends on: CashuBackend (mint operations)
│   ├── Depends on: WalletStorage (local persistence, pending ops)
│   ├── Depends on: Nip60WalletSync (cross-device sync)
│   ├── Depends on: WalletKeyManager (wallet identity)
│   ├── Key method: syncWallet() - THE sync function (NIP-60 is source of truth)
│   ├── Key method: recoverPendingOperations() - recover blinded ops on connect
│   ├── CRITICAL: Safe deletion pattern - always republish remaining proofs before deleteProofEvents()
│   ├── CRITICAL: pendingOpId must be cleared AFTER NIP-60 publish (or RecoveryToken fallback)
│   └── Used by: RiderViewModel, DriverViewModel, WalletDetailScreen, WalletSettingsScreen
│
├── CashuBackend (NUT-04/05/14 implementation)
│   ├── Depends on: cdk-kotlin library
│   ├── Depends on: WalletStorage (pending blinded operations)
│   ├── Connects to: Cashu Mint (HTTP REST)
│   ├── CRITICAL: All blinded ops save premints BEFORE request, return pendingOpId
│   ├── Caller clears pendingOpId after persisting proofs (NIP-60 or RecoveryToken)
│   └── Used by: WalletService
│
├── Nip60WalletSync (NIP-60 wallet backup - FULLY COMPLIANT)
│   ├── Depends on: NostrService (event publishing)
│   ├── Depends on: KeyManager (signing)
│   ├── Depends on: WalletKeyManager (wallet key backup)
│   ├── Kind 7375: {"mint":"...","proofs":[...]} - NIP-60 standard
│   ├── Kind 17375: [["privkey","..."],["mint","..."]] - NIP-60 standard
│   ├── IMPORTANT: One Kind 7375 event can contain MANY proofs (same eventId)
│   └── Used by: WalletService, Nip60WalletSyncAdapter
│
└── PaymentCrypto (preimage/hash utilities)
    └── Used by: RiderViewModel (escrow creation)
```

### Nostr Layer

```
Nostr Layer
├── NostrService (central event hub)
│   ├── Depends on: RelayManager (WebSocket connections)
│   ├── Depends on: KeyManager (event signing)
│   ├── Used by: RiderViewModel, DriverViewModel
│   ├── Used by: All SyncAdapters
│   └── Used by: Nip60WalletSync
│
├── RelayManager (connection pool)
│   ├── Connects to: Nostr Relays (WebSocket)
│   ├── Used by: NostrService
│   └── Used by: ProfileSyncManager
│
├── KeyManager (Nostr identity)
│   ├── Depends on: SecureKeyStorage
│   ├── Used by: NostrService
│   ├── Used by: ProfileSyncManager (shared singleton)
│   └── Used by: Nip60WalletSync
│
└── Event Models (events/*.kt)
    ├── 8 ride protocol events (Kind 30173, 3173, 3174, 3175, 30180, 30181, 3178, 3179)
    ├── 2 backup events (Kind 30174 history, 30177 profile)
    └── Used by: NostrService methods
```

### Profile Sync

```
Profile Sync
├── ProfileSyncManager (orchestrator singleton)
│   ├── Depends on: KeyManager (shared)
│   ├── Depends on: RelayManager (shared)
│   ├── Manages: All registered SyncAdapters
│   ├── Used by: MainActivity (both apps)
│   └── backupProfileData() - called by auto-backup observers
│
├── Nip60WalletSyncAdapter (order=0)
│   ├── Depends on: Nip60WalletSync
│   └── Restores: Cashu proofs (Kind 7375)
│
├── ProfileSyncAdapter (order=1) ★ UNIFIED
│   ├── Depends on: VehicleRepository (driver)
│   ├── Depends on: SavedLocationRepository (rider)
│   ├── Depends on: SettingsManager (settings sync)
│   ├── Depends on: NostrService
│   └── Restores: Kind 30177 (vehicles + locations + settings)
│
└── RideHistorySyncAdapter (order=2)
    ├── Depends on: RideHistoryRepository
    ├── Depends on: NostrService
    └── Restores: Ride history (Kind 30174)

Auto-Backup Flow (MainActivity observers):
├── Driver: vehicleRepository.vehicles → backupProfileData()
├── Rider: savedLocationRepo.savedLocations → backupProfileData()
└── Both: settingsManager.syncableSettingsHash → backupProfileData()
    └── syncableSettingsHash combines: displayCurrency, distanceUnit,
        notificationSettings, autoOpenNavigation, alwaysAskVehicle,
        paymentMethods, defaultPaymentMethod, mintUrl, customRelays
```

### State Machines

```
State Machines
├── RiderViewModel
│   ├── Depends on: NostrService (event pub/sub)
│   ├── Depends on: WalletService (payment locking)
│   ├── Depends on: RideHistoryRepository (ride storage)
│   ├── Depends on: SavedLocationRepository (location storage)
│   ├── Publishes: Kind 3173, 3175, 30181 events
│   └── Subscribes: Kind 30173, 3174, 30180, 3179 events
│
└── DriverViewModel
    ├── Depends on: NostrService (event pub/sub)
    ├── Depends on: WalletService (payment claiming)
    ├── Depends on: RideHistoryRepository (ride storage)
    ├── Depends on: VehicleRepository (vehicle data)
    ├── Publishes: Kind 30173, 3174, 30180, 3179 events
    └── Subscribes: Kind 3173, 3175, 30181 events
```

---

## Event Kind Reference

| Kind | Name | Publisher | Subscriber | Purpose |
|------|------|-----------|------------|---------|
| 30173 | Driver Availability | Driver | Rider | Driver broadcasts location/status + mint_url/payment_methods |
| 3173 | Ride Offer | Rider | Driver | Rider requests ride + mint_url/payment_method (encrypted) |
| 3174 | Ride Acceptance | Driver | Rider | Driver accepts + mint_url/payment_method |
| 3175 | Ride Confirmation | Rider | Driver | Rider confirms with PIN |
| 30180 | Driver Ride State | Driver | Rider | Status updates, PIN submission |
| 30181 | Rider Ride State | Rider | Driver | Location reveal, PIN verify, preimage |
| 3178 | Chat | Both | Both | In-ride messaging (encrypted) |
| 3179 | Cancellation | Both | Both | Ride cancellation |
| 30174 | Ride History | Self | Self | Backup (encrypted to self) |
| 30177 | Unified Profile | Self | Self | Vehicles, locations, settings + payment prefs |
| 7375 | Wallet Proofs | Self | Self | NIP-60 wallet proofs (encrypted) |
| 17375 | Wallet Metadata | Self | Self | NIP-60 wallet metadata (encrypted) |

### NIP-60 Wallet Event Formats (January 2026 - Fully Compliant)

**Kind 7375 - Proof Events** (JSON object):
```json
{"mint": "https://mint.example.com", "proofs": [{"id":"...","amount":1,"secret":"...","C":"..."}]}
```

**Kind 17375 - Wallet Metadata** (Array of tag-like pairs):
```json
[["privkey", "hex_wallet_key"], ["mint", "https://mint.example.com"], ["mnemonic", "word1 word2..."]]
```

Both are NIP-44 encrypted to user's pubkey. The `mnemonic` field is a custom extension for cdk-kotlin recovery.

### Multi-Mint Support (Issue #13 - Phase 1)

Protocol events now include payment method fields for multi-mint compatibility:

```
PaymentMethod enum (RideshareEventKinds.kt)
├── CASHU - Cashu ecash (NUT-14 HTLC)
├── LIGHTNING - Lightning Network direct
└── FIAT_CASH - Cash on delivery

Fields added to events:
├── Kind 30173 (Availability): mint_url, payment_methods[]
├── Kind 3173 (Offer): mint_url, payment_method
├── Kind 3174 (Acceptance): mint_url, payment_method
└── Kind 30177 (Profile): settings.paymentMethods[], settings.defaultPaymentMethod, settings.mintUrl
```

---

## Critical Connection Points

### Must Call Together

| Operation | Required Calls | Why |
|-----------|----------------|-----|
| Accept ride (driver) | `clearDriverStateHistory()` + `acceptOffer()` | Prevents phantom cancellation |
| Start new ride (rider) | `clearRiderStateHistory()` before new ride | Prevents history pollution |
| Login/Key import | `keyManager.refreshFromStorage()` on both NostrService AND ProfileSyncManager | Ensures shared key state |
| Wallet connection | `walletService.setNip60Sync()` after creating Nip60WalletSync | Enables cross-device sync |

### Cannot Be Removed Without Breaking

| Component | Used By | Impact if Removed |
|-----------|---------|-------------------|
| `KeyManager` (singleton) | NostrService, ProfileSyncManager, Nip60WalletSync | All signing/encryption breaks |
| `RelayManager` | NostrService, ProfileSyncManager | All Nostr communication breaks |
| `WalletService.balance` | WalletScreen (both apps), RiderViewModel | Balance display + ride checks break |
| `RideHistoryRepository` | Both ViewModels, HistorySyncAdapter | History tracking breaks |

---

## File Path Quick Reference

| Component | Path |
|-----------|------|
| **Rider ViewModel** | `rider-app/src/main/java/com/ridestr/rider/viewmodels/RiderViewModel.kt` |
| **Driver ViewModel** | `drivestr/src/main/java/com/drivestr/app/viewmodels/DriverViewModel.kt` |
| **NostrService** | `common/src/main/java/com/ridestr/common/nostr/NostrService.kt` |
| **WalletService** | `common/src/main/java/com/ridestr/common/payment/WalletService.kt` |
| **CashuBackend** | `common/src/main/java/com/ridestr/common/payment/cashu/CashuBackend.kt` |
| **ProfileSyncManager** | `common/src/main/java/com/ridestr/common/sync/ProfileSyncManager.kt` |
| **KeyManager** | `common/src/main/java/com/ridestr/common/nostr/keys/KeyManager.kt` |
| **RelayManager** | `common/src/main/java/com/ridestr/common/nostr/relay/RelayManager.kt` |
| **WalletDetailScreen** | `common/src/main/java/com/ridestr/common/ui/WalletDetailScreen.kt` |
| **WalletSettingsScreen** | `common/src/main/java/com/ridestr/common/ui/WalletSettingsScreen.kt` |
| **RideHistoryRepository** | `common/src/main/java/com/ridestr/common/data/RideHistoryRepository.kt` |
