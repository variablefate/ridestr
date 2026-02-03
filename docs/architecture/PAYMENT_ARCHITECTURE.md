# Ridestr Payment Architecture

**Last Updated**: 2026-02-02
**Status**: ✅ COMPLETE - Wallet, HTLC, P2PK Signing, Integration Wired, Phase 5 Reorganization

---

## Executive Summary

The Ridestr wallet uses **Cashu ecash** for trustless ride payments. The implementation consists of:

1. **cdk-kotlin** (Cashu Development Kit) - Core cryptographic operations
2. **NIP-60** - Cross-device wallet backup via Nostr relays
3. **NUT-14 HTLC** - Hash Time-Locked Contracts for ride escrow (PARTIAL)

---

## Implementation Status

| Feature | Status | Notes |
|---------|--------|-------|
| Deposits (NUT-04) | ✅ COMPLETE | `wallet.mintQuote()` + `wallet.mint()` |
| Withdrawals (NUT-05) | ✅ COMPLETE | `wallet.meltQuote()` + `wallet.melt()` |
| Balance Check | ✅ COMPLETE | `wallet.totalBalance()` via SQLite |
| NIP-60 Backup | ✅ COMPLETE | Kind 7375 events to Nostr relays |
| NIP-60 Restore | ✅ COMPLETE | Fetch Kind 7375, decrypt, verify with mint |
| Proof State (NUT-07) | ✅ COMPLETE | HTTP `/v1/checkstate` |
| HTLC Create (NUT-14) | ✅ COMPLETE | Uses `/v1/swap` with wallet pubkey |
| HTLC Claim (NUT-14) | ✅ COMPLETE | P2PK Schnorr signatures per-proof |
| ViewModel Integration | ✅ COMPLETE | Deferred locking after acceptance |

---

## File Structure

```
common/src/main/java/com/ridestr/common/payment/
├── cashu/
│   ├── CashuBackend.kt      # Core mint operations (cdk-kotlin + HTTP) - with region comments
│   ├── CashuCrypto.kt       # Cryptographic primitives (secp256k1, blind signatures)
│   ├── CashuProof.kt        # Proof data class
│   ├── CashuTokenCodec.kt   # Token encoding/decoding utilities (Phase 5 extraction)
│   └── Nip60WalletSync.kt   # NIP-60 Nostr sync (Kind 7375, 7376, 17375)
├── PaymentCrypto.kt         # Preimage/hash generation for HTLC
├── PaymentModels.kt         # Data classes (EscrowDetails, WalletBalance, etc.)
├── WalletKeyManager.kt      # Wallet keypair + mnemonic storage
├── WalletService.kt         # Orchestration layer (public API) - with region comments
└── WalletStorage.kt         # Local persistence (EncryptedSharedPreferences)
```

### Phase 5 Reorganization (February 2026)

**CashuTokenCodec** - Stateless utilities extracted from CashuBackend (~200 lines):
- `HtlcProof` data class
- `encodeHtlcProofsAsToken()` - Encode HTLC proofs as cashuA token
- `encodeProofsAsToken()` - Encode plain proofs as cashuA token
- `parseHtlcToken()` - Decode cashuA/cashuB tokens
- `extractPaymentHashFromSecret()` - Extract hash from NUT-10 HTLC secret
- `extractLocktimeFromSecret()` - Extract locktime from NUT-14 secret
- `extractRefundKeysFromSecret()` - Extract refund keys from NUT-14 secret

**Region Comments** - Both CashuBackend and WalletService now have organized sections:
- INSTANCE STATE, LIFECYCLE & CONNECTION
- HTLC ESCROW / HTLC PAYMENT FLOWS (with critical invariants documented)
- MINTING, MELTING, RECOVERY, DIAGNOSTICS, etc.

**Correlation ID Logging** - Payment operations now log with ride correlation IDs:
- RiderViewModel: `[RIDE xxxxxxxx] Locking HTLC...`
- DriverViewModel: `[RIDE xxxxxxxx] Claiming HTLC...`
- Uses existing identifiers: `acceptanceEventId` (rider), `confirmationEventId` (driver)

---

## Architecture Layers

### Layer 1: CashuBackend (Low-level mint operations)

**File**: `CashuBackend.kt`

**Responsibilities**:
- Manages cdk-kotlin `Wallet` instance
- Handles mint connection and capability verification (NUT-06)
- Minting tokens (NUT-04) via `wallet.mint()`
- Melting tokens (NUT-05) via `wallet.melt()`
- HTLC creation and claiming (NUT-14) via HTTP `/v1/swap`
- Proof state verification (NUT-07) via HTTP `/v1/checkstate`
- Delegates token encoding/decoding to CashuTokenCodec

**Region Organization** (Phase 5):
- INSTANCE STATE - WebSocket, wallet, connection state
- LIFECYCLE & CONNECTION - connect(), disconnect(), ensureConnected()
- MINT API - QUOTES (NUT-04/05) - getMintQuote(), getMeltQuote()
- PROOF VERIFICATION (NUT-07) - verifyProofStatesBySecret()
- HTLC ESCROW (NUT-14) - **with critical invariants documented**
- TOKEN ENCODING & RECEIPT - delegates to CashuTokenCodec
- MINTING (NUT-04) - mintTokens()
- MELTING (NUT-05) - meltTokens()
- KEYSET MANAGEMENT & DEPOSIT RECOVERY
- RECOVERY (NUT-09) - restoreProofs()

**Key Functions**:

| Function | Purpose | Status |
|----------|---------|--------|
| `connect(mintUrl)` | Initialize cdk-kotlin wallet | ✅ |
| `getMintQuote(amount)` | Request deposit invoice | ✅ |
| `mintTokens(quoteId, amount)` | Create proofs after payment | ✅ |
| `getMeltQuote(bolt11)` | Request withdrawal quote | ✅ |
| `meltTokens(quoteId)` | Pay Lightning invoice | ✅ |
| `getCdkBalance()` | Get total proof balance | ✅ |
| `createHtlcTokenFromProofs()` | Create HTLC escrow | ✅ |
| `claimHtlcToken(token, preimage)` | Claim HTLC | ✅ |
| `verifyProofStatesBySecret()` | NUT-07 proof verification | ✅ |

### Layer 1.5: CashuTokenCodec (Stateless utilities)

**File**: `CashuTokenCodec.kt` (extracted in Phase 5)

**Responsibilities**:
- Pure functions for Cashu token encoding/decoding
- HTLC secret parsing (NUT-10/14)
- No instance state - all methods are stateless

**Key Functions**:

| Function | Purpose |
|----------|---------|
| `encodeHtlcProofsAsToken()` | Encode HTLC proofs as cashuA token |
| `encodeProofsAsToken()` | Encode plain proofs as cashuA token |
| `parseHtlcToken()` | Decode cashuA/cashuB tokens |
| `extractPaymentHashFromSecret()` | Extract hash from NUT-10 secret |
| `extractLocktimeFromSecret()` | Extract locktime from NUT-14 secret |
| `extractRefundKeysFromSecret()` | Extract refund keys from NUT-14 secret |

### Layer 2: WalletService (Orchestration)

**File**: `WalletService.kt`

**Responsibilities**:
- Public API for wallet operations (hides Cashu details)
- Coordinates CashuBackend, WalletStorage, Nip60WalletSync
- Manages balance state flows
- Handles deposit recovery

**Key State Flows**:
```kotlin
val balance: StateFlow<WalletBalance>           // Current balance
val isConnected: StateFlow<Boolean>             // Mint connection state
val currentMintName: StateFlow<String?>         // Friendly mint name
val transactions: StateFlow<List<PaymentTransaction>>  // History
```

**Balance Priority Order**:
1. **NUT-07 Verified** - `getVerifiedBalance()` - Most authoritative
2. **cdk-kotlin Local** - `getCdkBalance()` - Recent transactions
3. **NIP-60 Nostr** - `nip60Sync.getBalance()` - May be stale
4. **Cached** - `walletStorage.getCachedBalance()` - Last resort

### Layer 3: Nip60WalletSync (Cross-device sync)

**File**: `Nip60WalletSync.kt`

**NIP-60 Event Kinds**:
- **Kind 17375**: Wallet metadata (replaceable, d-tag: "cashu-wallet")
- **Kind 7375**: Unspent proofs (encrypted to self)
- **Kind 7376**: Spending history (optional audit trail)

**Key Functions**:
```kotlin
publishProofs(proofs, mintUrl)  // Batches ALL proofs into ONE Kind 7375 event
fetchProofs(forceRefresh)       // Returns deduplicated Nip60Proof list
restoreFromNostr()              // Full wallet restore
```

### Layer 4: WalletStorage (Local persistence)

**File**: `WalletStorage.kt`

Uses `EncryptedSharedPreferences` (AES256_GCM) for:
- `mint_url` - Selected mint URL
- `balance_*` - Cached balance values
- `transactions` - Transaction history (last 100)
- `pending_deposits` - Deposits awaiting minting

### Layer 5: WalletKeyManager (Key management)

**File**: `WalletKeyManager.kt`

Manages TWO separate keys:

1. **Wallet Keypair** (secp256k1)
   - Used for P2PK spending conditions
   - Signs HTLC witness for NUT-14 via `signSchnorr()`
   - Stored in `EncryptedSharedPreferences`

2. **Mnemonic** (BIP-39)
   - Seeds the cdk-kotlin wallet
   - Deterministically derives proof blinding factors

---

## Data Flow Diagrams

### Deposit Flow (NUT-04) - WORKING

```
User                 WalletService         CashuBackend          cdk-kotlin         Mint
  │                       │                     │                    │                │
  │ requestDeposit(100)   │                     │                    │                │
  │──────────────────────>│                     │                    │                │
  │                       │ getMintQuote(100)   │                    │                │
  │                       │────────────────────>│ mintQuote(100)     │                │
  │                       │                     │───────────────────>│ GET /v1/mint/quote
  │                       │                     │                    │───────────────>│
  │                       │                     │                    │ {quote, invoice}
  │                       │                     │<───────────────────│<───────────────│
  │ Show Lightning Invoice│<────────────────────│                    │                │
  │<──────────────────────│                     │                    │                │
  │                       │                     │                    │                │
  │ [User pays invoice]   │                     │                    │                │
  │                       │                     │                    │                │
  │ completeDeposit()     │                     │                    │                │
  │──────────────────────>│ mintTokens(quote)   │                    │                │
  │                       │────────────────────>│ mint(quote)        │                │
  │                       │                     │───────────────────>│ POST /v1/mint
  │                       │                     │                    │───────────────>│
  │                       │                     │                    │ [blind sigs]   │
  │                       │                     │ [unblind→proofs]   │<───────────────│
  │                       │                     │<───────────────────│ [save to SQLite]
  │                       │ publishProofs()     │                    │                │
  │                       │────────────────────>│                    │                │
  │                       │ [Kind 7375 to Nostr]│                    │                │
  │ Balance updated       │<────────────────────│                    │                │
  │<──────────────────────│                     │                    │                │
```

### HTLC Escrow Flow (NUT-14) - COMPLETE

```
Rider                WalletService         CashuBackend                   Mint
  │                       │                     │                           │
  │ lockForRide(100, hash, driverPK)            │                           │
  │──────────────────────>│                     │                           │
  │                       │ selectProofsForSpending(100)                    │
  │                       │────────────────────>│                           │
  │                       │ [NIP-60 proofs]     │                           │
  │                       │<────────────────────│                           │
  │                       │                     │                           │
  │                       │ verifyProofStatesBySecret(secrets)  [NUT-07]    │
  │                       │────────────────────>│ POST /v1/checkstate       │
  │                       │                     │──────────────────────────>│
  │                       │                     │ [UNSPENT/SPENT states]    │
  │                       │<────────────────────│<──────────────────────────│
  │                       │ [If SPENT: delete NIP-60 events, retry]         │
  │                       │                     │                           │
  │                       │ createHtlcTokenFromProofs(proofs, hash, driverPK)
  │                       │────────────────────>│                           │
  │                       │                     │ POST /v1/swap             │
  │                       │                     │ [plain inputs→HTLC outputs]
  │                       │                     │──────────────────────────>│
  │                       │                     │ [blind sigs]              │
  │                       │                     │<──────────────────────────│
  │                       │ [HTLC token + change proofs]                    │
  │                       │<────────────────────│                           │
  │ EscrowLock (token)    │                     │                           │
  │<──────────────────────│                     │  ✅ COMPLETE               │

Driver               WalletService         CashuBackend                   Mint
  │                       │                     │                           │
  │ claimHtlcPayment(token, preimage)           │                           │
  │──────────────────────>│ claimHtlcToken(token, preimage)                 │
  │                       │────────────────────>│                           │
  │                       │                     │ POST /v1/swap             │
  │                       │                     │ [HTLC+witness+P2PK sig]   │
  │                       │                     │──────────────────────────>│
  │                       │                     │ [plain proofs]            │
  │                       │<────────────────────│<──────────────────────────│
  │ SettlementResult      │                     │  ✅ COMPLETE               │
  │<──────────────────────│                     │                           │
```

---

## HTLC Implementation - COMPLETE

### NUT-10 Secret Format (`CashuBackend.kt:636-680`)
```json
["HTLC", {
  "nonce": "<random_hex>",
  "data": "<payment_hash_64hex>",
  "tags": [
    ["pubkeys", "<driver_wallet_pubkey>"],
    ["locktime", "<unix_timestamp>"],
    ["refund", "<rider_pubkey>"]
  ]
}]
```

### HTLC Creation Flow
1. Driver accepts ride → sends `wallet_pubkey` in acceptance event
2. Rider receives acceptance → locks HTLC with driver's **wallet** pubkey
3. Create HTLC secret with payment_hash
4. Generate blinded outputs
5. POST `/v1/swap` to mint
6. Unblind signatures
7. Return HTLC token + change proofs

### P2PK Signing - IMPLEMENTED

**WalletKeypair.signSchnorr()** (`WalletKeyManager.kt:308`):
```kotlin
fun signSchnorr(messageHash: ByteArray): String? {
    require(messageHash.size == 32) { "Message hash must be 32 bytes" }
    return try {
        val signature = fr.acinq.secp256k1.Secp256k1.signSchnorr(
            messageHash,
            getPrivateKeyBytes(),
            null  // Deterministic
        )
        signature.toHexKey()
    } catch (e: Exception) { null }
}
```

**signP2pkProof()** (`CashuBackend.kt`):
```kotlin
private fun signP2pkProof(secret: String, proofC: String): String? {
    // NUT-11: Sign SHA256(secret) only - proofC kept for logging
    val messageHash = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray())
    return walletKeyManager.getOrCreateWalletKeypair().signSchnorr(messageHash)
}
```

**Per-Proof Witness** (`CashuBackend.kt:370`):
```kotlin
val proofsWithWitness = htlcProofs.map { proof ->
    val sig = signP2pkProof(proof.secret, proof.C)
    val witnessJson = JSONObject().apply {
        put("preimage", preimage)
        put("signatures", JSONArray().apply { if (sig != null) put(sig) })
    }.toString()
    proof to witnessJson
}
```

---

## Critical Design: Wallet Pubkey Handshake

### The Problem
Driver's **Nostr key** (identity) ≠ **wallet key** (P2PK signing). Original flow locked HTLC with Nostr key, but driver signed claims with wallet key → keys mismatch → claim rejected.

### The Solution
1. `RideAcceptanceEvent` includes `wallet_pubkey` field
2. Driver sends wallet pubkey when accepting ride
3. Rider uses `acceptance.walletPubKey` for HTLC P2PK condition
4. Driver claims with wallet key signature → keys match → success

### Deferred HTLC Locking
HTLC is locked **AFTER** acceptance (not before):
```kotlin
// RiderViewModel.autoConfirmRide() - line 1823
val driverP2pkKey = acceptance.walletPubKey ?: acceptance.driverPubKey  // fallback
val escrowToken = walletService?.lockForRide(fareAmount, paymentHash, driverP2pkKey, ...)
```

This ensures:
- Rider knows driver's wallet pubkey before locking
- P2PK condition matches the key driver will sign with

---

## ViewModel Integration - COMPLETE

### RiderViewModel Integration

**Preimage Generation** (`sendOfferToDriver()` / `broadcastRideRequest()`):
```kotlin
val preimage = PaymentCrypto.generatePreimage()
val paymentHash = PaymentCrypto.computePaymentHash(preimage)
// Store in state: activePreimage, activePaymentHash
```

**Deferred HTLC Locking** (`autoConfirmRide()` - line 1823):
```kotlin
val driverP2pkKey = acceptance.walletPubKey ?: acceptance.driverPubKey
val escrowToken = walletService?.lockForRide(
    amountSats = fareAmount,
    paymentHash = paymentHash,
    driverPubKey = driverP2pkKey,
    expirySeconds = 7200L
)?.htlcToken
```

**Preimage Sharing** (`sharePreimageWithDriver()`):
```kotlin
val encryptedPreimage = nostrService.encryptForUser(preimage, driverPubKey)
val preimageAction = RiderRideStateEvent.createPreimageShareAction(
    preimageEncrypted = encryptedPreimage,
    escrowToken = escrowToken
)
riderStateHistory.add(preimageAction)
nostrService.publishRiderRideState(confirmationEventId, riderStateHistory)
```

### DriverViewModel Integration

**Wallet Pubkey in Acceptance** (`acceptOffer()` / `acceptBroadcastRequest()`):
```kotlin
val walletPubKey = walletService?.getWalletPubKey()
val eventId = nostrService.acceptRide(offer, walletPubKey)
```

**Preimage Handler** (`handlePreimageShare()`):
```kotlin
val preimage = signer.nip44Decrypt(action.preimageEncrypted, riderPubKey)
if (PaymentCrypto.verifyPreimage(preimage, activePaymentHash)) {
    activePreimage = preimage
    // Ready to claim at dropoff
}
```

**HTLC Claim** (`completeRide()` - line 1621):
```kotlin
val result = walletService?.claimHtlcPayment(escrowToken, activePreimage)
if (result != null) {
    // Publish settlement proof via Kind 30180
}
```

---

## Known Issues & Workarounds

### Stale NIP-60 Proofs ("Token already spent")

**Problem**: NIP-60 proof events persist after proofs are spent (e.g., relay connection lost during cleanup).

**Solution**: NUT-07 verification BEFORE swap (`WalletService.kt:324-357`):
```kotlin
// Step 1.5: Verify selected proofs with mint (NUT-07)
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

### Fresh Install Restore

**Problem**: New device generates new mnemonic (can't access old cdk-kotlin proofs).

**Current Workaround**: NIP-60 proofs are the SOURCE OF TRUTH. Spending uses NIP-60 proofs directly via `createHtlcTokenFromProofs()`.

### Balance Discrepancy

**Problem**: Local vs NIP-60 vs NUT-07 verified can differ.

**Solution**: Use NUT-07 `getVerifiedBalance()` as authoritative.

### Amount Extraction from CDK

**Problem**: `CdkAmount` is a uniffi-generated wrapper class.

**Workaround**: Reflection to access `value` field:
```kotlin
val valueField = amountClass.getDeclaredField("value")
valueField.isAccessible = true
return (valueField.get(amount) as Long)
```

---

## Correlation ID Logging

Payment operations use ride correlation IDs to enable end-to-end tracing through logs.

### Design Decision: Different IDs at Different Stages

| ViewModel | ID Used | Why |
|-----------|---------|-----|
| **RiderViewModel** | `acceptanceEventId` | HTLC locked pre-confirmation; `confirmationEventId` doesn't exist yet |
| **DriverViewModel** | `confirmationEventId` | HTLC claimed post-confirmation |

**This is intentional design, not a bug.** The rider locks the HTLC in `autoConfirmRide()` immediately after receiving acceptance but BEFORE sending the confirmation event. At that point, only `acceptanceEventId` exists.

### Log Format

```
[RIDE xxxxxxxx] Locking HTLC: fareAmount=100, paymentHash=abcd1234...
[RIDE xxxxxxxx] Claiming HTLC: paymentHash=abcd1234...
```

The 8-character prefix is the first 8 characters of the event ID.

### End-to-End Trace

To trace a ride's payment flow:

1. Find rider's `[RIDE xxxxxxxx]` log entry during `autoConfirmRide()` → shows HTLC lock
2. The `xxxxxxxx` is from `acceptanceEventId`
3. Find driver's `[RIDE yyyyyyyy]` log entry during `completeRide()` → shows HTLC claim
4. The `yyyyyyyy` is from `confirmationEventId`
5. Cross-reference: `acceptanceEventId` is logged in rider's confirmation event creation

### Implementation Locations

- **RiderViewModel:2970** - `[RIDE $rideCorrelationId] Locking HTLC...`
- **DriverViewModel:2300-2301** - `[RIDE $rideCorrelationId] Claiming HTLC...`

---

## Related Documentation

- [NOSTR_EVENTS.md](../protocol/NOSTR_EVENTS.md) - Payment-related event fields
- [STATE_MACHINES.md](STATE_MACHINES.md) - Ride state transitions
- [RIDER_VIEWMODEL.md](../viewmodels/RIDER_VIEWMODEL.md) - Integration points
- [DRIVER_VIEWMODEL.md](../viewmodels/DRIVER_VIEWMODEL.md) - Integration points
- [COMPATIBILITY_CONTRACTS.md](COMPATIBILITY_CONTRACTS.md) - API stability contracts
- [PAYMENT_SAFETY.md](PAYMENT_SAFETY.md) - Payment modification checklist
