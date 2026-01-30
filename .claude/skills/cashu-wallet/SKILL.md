---
name: cashu-wallet
description: Debugging and extending the Ridestr Cashu wallet implementation. Use when working with balance issues, payment problems, HTLC escrow, wallet sync, ecash tokens, NIP-60 backup, deposits, withdrawals, proof management, P2PK signatures, or mint API errors. Covers NUT-04/05/14 operations and cdk-kotlin integration.
---

# Cashu Wallet Implementation Guide

## Overview

The Ridestr Cashu wallet provides trustless ride payments via NUT-14 HTLC escrow with P2PK signatures. This skill covers debugging, extending, and maintaining the payment system.

## Implementation Status

| Component | Status |
|-----------|--------|
| Deposits (NUT-04) | ✅ Complete (cdk-kotlin + custom recovery) |
| Withdrawals (NUT-05) | ✅ Complete (change outputs fixed, BOLT11 only) |
| NIP-60 Sync | ✅ Complete (with retry logic + recovery token fallback) |
| HTLC Create (NUT-14) | ✅ Complete |
| HTLC Claim (NUT-14) | ✅ Complete with P2PK signatures |
| HTLC Refund (NUT-14) | ✅ Complete (auto-refund on connect) |
| NUT-07 Verification | ✅ Complete (hashToCurve fixed) |
| NUT-13 Deterministic Secrets | ✅ Complete (January 2026 - seed-based recovery) |
| NUT-09 Restore | ✅ Complete (January 2026 - mint scanning for recovery) |
| ViewModel Integration | ✅ Complete (deferred locking) |
| Recovery Token Fallback | ✅ Complete (January 2026 - SharedPreferences persistence) |
| Cross-Mint Bridge Failure | ✅ Complete (auto-cancel ride on failure) |

---

## cdk-kotlin Usage Architecture

**Key insight**: cdk-kotlin is used ONLY for deposits and quote tracking. All withdrawals, HTLC operations, and proof management use our custom implementations.

### What We Actually Use cdk-kotlin For ✅

| Operation | Method | Why cdk-kotlin | Actual Usage |
|-----------|--------|----------------|--------------|
| Deposit Quote | `wallet.mintQuote()` | `wallet.mint()` only recognizes its own quotes | **Primary** |
| Deposit Execution | `wallet.mint()` | Handles blinding crypto correctly | **Primary** (fallback: `recoverDeposit()`) |
| Melt Quote | `wallet.meltQuote()` | Quote ID tracking for fee calculation | **Primary** |
| Local Balance | `wallet.totalBalance()` | Diagnostic only | **Not used for real balance** |

### What We DON'T Use cdk-kotlin For ❌

| Operation | Our Method | Why Custom |
|-----------|------------|------------|
| **Withdrawals** | `meltWithProofs()` | NIP-60 proofs aren't in cdk-kotlin's SQLite |
| **HTLC Lock** | `createHtlcTokenFromProofs()` | cdk-kotlin doesn't expose P2PK/HTLC conditions |
| **HTLC Claim** | `claimHtlcTokenWithProofs()` | cdk-kotlin doesn't expose witness API |
| **HTLC Refund** | `refundExpiredHtlc()` | cdk-kotlin doesn't expose refund path |
| **Deposit Recovery** | `recoverDeposit()` | Fallback when cdk-kotlin fails |
| **NUT-07 Verification** | `verifyProofStatesBySecret()` | cdk-kotlin doesn't expose checkstate |
| **NIP-60 Sync** | `Nip60WalletSync.kt` | cdk-kotlin doesn't know about NIP-60 |
| **Proof Selection** | `selectProofsForSpending()` | Must use NIP-60 proofs, not local SQLite |

**IMPORTANT**: `wallet.melt()` is NOT used for real withdrawals. All withdrawals use `meltWithProofs()` with NIP-60 proofs.

### Architecture Diagram
```
                    ┌─────────────────────┐
                    │   Nostr Relays      │
                    │  (NIP-60 Proofs)    │  ← SOURCE OF TRUTH
                    └─────────────────────┘
                             ▲
                             │
                    Nip60WalletSync
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
   cdk-kotlin        WalletService          ViewModels
   (Deposits only)   (Orchestration)        (UI State)
        │                    │                    │
        └────────────────────┼────────────────────┘
          (Quote+Mint)    (Logic)        (Display)
```

**cdk-kotlin = Deposits Only** - Quote tracking and `wallet.mint()` for deposits.
**NIP-60 = THE Wallet** - All proofs, all spending, cross-device sync.
**Custom HTTP = Everything Else** - Withdrawals, HTLC, verification.

---

## Critical Design: Separate Keys

**Nostr Key vs Wallet Key**: These are DIFFERENT keys for security isolation.

| Key | Purpose | Location |
|-----|---------|----------|
| Nostr Key | Identity, event signing, NIP-44 encryption | `KeyManager` |
| Wallet Key | P2PK escrow claims (BIP-340 Schnorr) | `WalletKeyManager` |

**The Flow**:
1. Driver accepts ride → sends `wallet_pubkey` in acceptance event
2. Rider receives acceptance → locks HTLC with driver's **wallet** pubkey
3. Driver claims HTLC with wallet key signature → keys match → success

**If keys mismatch**: Mint returns `"proofs could not be verified"` (code 10003)

### Cross-Device Wallet Key Backup (January 2026 Fix)

**Problem**: Wallet key was device-specific. When user imported nsec on new device:
- New device generated NEW wallet key
- Old HTLCs couldn't be claimed (key mismatch)
- `wallet_pubkey` in acceptance events didn't match new device's key

**Solution**: Backup wallet key and mnemonic to NIP-60 Kind 17375 metadata.

**NIP-60 Standard Format** (array of tag-like pairs, NIP-44 encrypted):
```json
[
  ["privkey", "hex_private_key"],          // NIP-60 standard: For P2PK claims
  ["mint", "https://mint.example.com"],    // NIP-60 standard: Mint URL
  ["mnemonic", "word1 word2 ..."]          // Custom extension: cdk-kotlin seed
]
```

**Key Files**:
| File | Function | Purpose |
|------|----------|---------|
| `Nip60WalletSync.kt:456` | `publishWalletMetadata()` | Writes NIP-60 array format |
| `Nip60WalletSync.kt:602` | `restoreFromNostr()` | Reads NIP-60 + legacy formats |
| `Nip60WalletSync.kt:695` | `parseWalletMetadata()` | Format detection & parsing |
| `WalletKeyManager.kt:217` | `importPrivateKey()` | Imports wallet key from backup |
| `WalletKeyManager.kt:241` | `importMnemonic()` | Imports mnemonic from backup |

**Format Compatibility**:
- **Writes**: NIP-60 standard array format (interoperable)
- **Reads**: Both NIP-60 array AND legacy JSON object (backwards compatible)

**Security**: The entire content is NIP-44 encrypted to the user's own pubkey. Only the user with their nsec can decrypt this data.

**Verification Logs**:
```
Parsed NIP-60 standard format metadata
Decrypted wallet metadata: mint=..., hasWalletKey=true, hasMnemonic=true
Restored wallet key from NIP-60 backup (pubkey: 02a1b2c3...)
Restored mnemonic from NIP-60 backup
```

---

## NIP-60 Compliance (January 2026)

Both wallet event types are now fully NIP-60 compliant and interoperable.

### Kind 17375 - Wallet Metadata
```json
[
  ["privkey", "hex_private_key"],
  ["mint", "https://mint.example.com"],
  ["mnemonic", "word1 word2 ..."]  // Custom extension
]
```
- ✅ Array of tag-like pairs (NIP-60 standard)
- ✅ `privkey` field for P2PK claims
- ✅ `mint` field for mint URL
- ⚪ `mnemonic` is custom extension (ignored by other wallets)

### Kind 7375 - Proof Events
```json
{
  "mint": "https://mint.example.com",
  "proofs": [{"id": "...", "amount": 1, "secret": "...", "C": "..."}]
}
```
- ✅ JSON object with `mint` and `proofs` (NIP-60 standard)
- ⚪ `unit` omitted (defaults to "sat" per spec)
- ⚪ `del` omitted (we use NIP-09 deletion instead)

### Backwards Compatibility
- **Writes**: NIP-60 standard format
- **Reads**: Both NIP-60 array AND legacy JSON object formats

---

## NUT-13 Deterministic Secrets (January 2026)

All proof secrets are now derived deterministically from the BIP-39 mnemonic. This enables seed-based fund recovery even if NIP-60 data is lost.

### How It Works

**Derivation Formula** (per NUT-13 spec):
```
domain = "Cashu_KDF_HMAC_SHA256" (UTF-8 bytes)
secret = HMAC-SHA256(seed, domain || keyset_id || counter || 0x00)
r = HMAC-SHA256(seed, domain || keyset_id || counter || 0x01) mod N
```

Where:
- `seed` = BIP-39 seed from mnemonic (PBKDF2-SHA512, 2048 iterations)
- `keyset_id` = keyset ID as raw bytes (hex decoded)
- `counter` = 64-bit big-endian counter
- `N` = secp256k1 curve order

### Counter Management

- **Per-keyset counters** (not per-mint) - stored in `WalletStorage`
- **Auto-increment** after each successful mint operation
- **Backed up to NIP-60** in Kind 17375 metadata:
```json
[
  ["privkey", "..."],
  ["mint", "..."],
  ["mnemonic", "..."],
  ["counters", "keyset1:42", "keyset2:17", "..."]
]
```

### Key Files

| File | Function | Purpose |
|------|----------|---------|
| `CashuCrypto.kt:mnemonicToSeed()` | BIP-39 | Mnemonic → 512-bit seed |
| `CashuCrypto.kt:deriveSecrets()` | NUT-13 | Seed + keyset + counter → secret + r |
| `CashuCrypto.kt:derivePreMintSecret()` | NUT-13 | Full PreMintSecret with Y and B_ |
| `WalletStorage.kt:getCounter()` | Counter | Read per-keyset counter |
| `WalletStorage.kt:incrementCounter()` | Counter | Atomic increment + return |
| `CashuBackend.kt:generatePreMintSecrets()` | Integration | Uses deterministic derivation |
| `Nip60WalletSync.kt:publishWalletMetadata()` | Backup | Counters to NIP-60 |

### What Uses Deterministic Derivation

| Operation | Deterministic? | Notes |
|-----------|---------------|-------|
| Deposit minting | ✅ Yes | All new proofs |
| Swap change outputs | ✅ Yes | Change back to sender |
| HTLC claim outputs | ✅ Yes | Driver's claimed proofs |
| HTLC refund outputs | ✅ Yes | Rider's refunded proofs |
| HTLC locked proofs | ❌ No | HTLC JSON secret (intentional) |

**HTLC locked proofs** use special `["HTLC", {...}]` JSON secrets - this is correct because:
- They are conditional (require preimage OR locktime expiry)
- Once spent (claimed or refunded), resulting proofs ARE deterministic
- HTLC data is stored in `PendingHtlc` for recovery

### No Random Fallback

If the wallet seed isn't available, `generatePreMintSecrets()` throws:
```kotlin
throw IllegalStateException(
    "Wallet seed not initialized. Cannot create recoverable proofs."
)
```

This ensures no non-recoverable proofs are ever created during normal operation.

---

## NUT-09 Seed Recovery (January 2026)

If NIP-60 data is lost, funds can be recovered by scanning the mint.

### Recovery Function

`WalletService.recoverFromSeed()`:
1. Get all active keysets from mint (NUT-02 `/v1/keysets`)
2. For each keyset, iterate counters in batches of 100
3. Generate deterministic blinded messages for all denominations
4. Call NUT-09 `/v1/restore` to get any signatures mint has
5. Unblind signatures to get proofs
6. Filter unspent via NUT-07 `/v1/checkstate`
7. Publish unspent proofs to NIP-60
8. Update counter storage to highest scanned value

### UI Access

Wallet Settings → Advanced → "Recover from Seed" button

### When to Use

- Device lost/reset AND NIP-60 data unavailable from relays
- Reinstalled app with same mnemonic
- Proofs somehow missing from NIP-60

### Key Files

| File | Function |
|------|----------|
| `WalletService.kt:recoverFromSeed()` | Orchestrates recovery |
| `CashuBackend.kt:restoreProofs()` | NUT-09 `/v1/restore` call |
| `CashuBackend.kt:filterUnspentProofs()` | NUT-07 filtering |
| `CashuBackend.kt:getActiveKeysetIds()` | NUT-02 keyset list |
| `WalletSettingsScreen.kt` | UI button |

---

## Wallet Sync Architecture

**NIP-60 IS the wallet** - cdk-kotlin is only used for mint API calls (deposit/withdraw).

### The Sync Function

`WalletService.syncWallet()` - THE sync function that handles everything:
- Fetches ALL NIP-60 proofs (regardless of stored mint URL)
- Tries verification at current mint first
- Auto-migrates proof URLs when mint URL changed
- Cleans up spent proofs from NIP-60
- Updates displayed balance
- Called by: `connect()`, `changeMintUrl()`, `refreshBalance()`, UI "Sync Wallet" button

### Mint URL Change Flow
When user changes mint via `changeMintUrl()`:
1. Connect to new mint
2. `syncWallet()` verifies proofs + migrates URLs (Kind 7375)
3. `publishWalletMetadata()` updates metadata with new URL (Kind 17375)
4. Cross-device restore now gets correct mint URL

### Key Files
| Location | What |
|----------|------|
| `WalletService.kt:285` | `changeMintUrl()` - handles mint switching |
| `WalletService.kt:382` | `syncWallet()` definition |
| `WalletSettingsScreen.kt` | Settings → Wallet UI (sync, change mint, diagnostics) |
| `Nip60WalletSync.kt:456` | `publishWalletMetadata()` - NIP-60 format |
| `Nip60WalletSync.kt` | `republishProofsWithNewMint()` for URL migration |

### Why Not cdk-kotlin?

Deep dive revealed: `wallet.receive()` does NOT exist in cdk-kotlin 0.14.3+. The architecture is:
- **NIP-60 IS the wallet store** (not cdk-kotlin)
- cdk-kotlin is only used for: `wallet.mint()` (deposits), `wallet.melt()` (withdrawals)
- All spending uses NIP-60 proofs directly via `selectProofsForSpending()`
- Swapped proofs are NOT stored in cdk-kotlin - they go to NIP-60

This means: The "cdk-kotlin balance = 0" diagnostic was **misleading**. cdk-kotlin was never meant to hold all proofs.

---

## HTLC Escrow Flow

### Creation (Rider Side)
```
1. Generate preimage + paymentHash in sendOfferToDriver()/broadcastRideRequest()
2. Store in state: activePreimage, activePaymentHash
3. Driver accepts → sends wallet_pubkey in Kind 3174
4. Rider receives acceptance → autoConfirmRide() locks HTLC
5. lockForRide(fareAmount, paymentHash, acceptance.walletPubKey, preimage=activePreimage)
6. PendingHtlc saved to WalletStorage with preimage for future-proof refund
```

### Claim (Driver Side)
```
1. Driver receives preimage via Kind 30181 PREIMAGE_SHARE
2. handlePreimageShare() decrypts and verifies preimage
3. completeRide() calls claimHtlcPayment(escrowToken, preimage)
4. P2PK signature created per proof
5. POST /v1/swap with witness → plain proofs returned
```

### Refund (Rider Side - After Locktime Expires)
```
1. On wallet connect: WalletService checks for refundable HTLCs
2. If locktime < now AND status == LOCKED → attempt refund
3. refundExpiredHtlc(htlcToken, riderPubKey, preimage) signs with rider's wallet key
4. Witness uses stored preimage if available, else zeros fallback for mint compatibility
5. POST /v1/swap with refund witness → plain proofs returned
6. Proofs published to NIP-60, status marked REFUNDED
```

### Ride Completion (Status Update)
```
1. Driver sends DriverStatusType.COMPLETED
2. Rider's handleRideCompletion() receives it
3. Calls markHtlcClaimedByPaymentHash(activePaymentHash)
4. PendingHtlc status updated to CLAIMED
5. No false refund attempts on next connect
```

### P2PK Signature Creation
```kotlin
// WalletKeypair.signSchnorr() - BIP-340 Schnorr
fun signSchnorr(messageHash: ByteArray): String? {
    val signature = fr.acinq.secp256k1.Secp256k1.signSchnorr(
        messageHash, getPrivateKeyBytes(), null
    )
    return signature.toHexKey()
}

// CashuBackend.signP2pkProof() - message = SHA256(secret || C)
private fun signP2pkProof(secret: String, proofC: String): String? {
    val messageHash = MessageDigest.getInstance("SHA-256")
        .digest((secret + proofC).toByteArray())
    return walletKeyManager.getOrCreateWalletKeypair().signSchnorr(messageHash)
}
```

---

## HTLC Refund Implementation

### Data Model
```kotlin
// PaymentModels.kt
data class PendingHtlc(
    val escrowId: String,
    val htlcToken: String,
    val amountSats: Long,
    val locktime: Long,
    val riderPubKey: String,
    val paymentHash: String,
    val preimage: String? = null,  // For refund if mint requires it (NUT-14 future-proofing)
    val rideId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: PendingHtlcStatus = PendingHtlcStatus.LOCKED
) {
    fun isRefundable(): Boolean = System.currentTimeMillis() / 1000 > locktime
    fun isActive(): Boolean = status == PendingHtlcStatus.LOCKED
}

enum class PendingHtlcStatus { LOCKED, CLAIMED, REFUNDED, FAILED }
```

**Preimage Storage** (January 2026): The `preimage` field stores the real preimage for future-proof refunds. NUT-14 spec says refund path after locktime should work with signature alone, but some mints (e.g., themilkroad.org) require the preimage field even for refunds. Old HTLCs without stored preimage fall back to zeros workaround.

### Storage (WalletStorage.kt)
- `savePendingHtlc(htlc)` - Save when HTLC locked
- `getPendingHtlcs()` - Get all pending HTLCs
- `getRefundableHtlcs()` - Get expired + still active
- `updateHtlcStatus(escrowId, status)` - Update on claim/refund
- `cleanupResolvedHtlcs()` - Remove old resolved entries

### Refund Flow (WalletService + CashuBackend)

**WalletService.connect()** - Auto-refund on connect:
```kotlin
// Check for and refund any expired HTLCs on connect
val refundable = walletStorage.getRefundableHtlcs()
if (refundable.isNotEmpty()) {
    val results = refundExpiredHtlcs()
    // Logs success count
}
```

**CashuBackend.refundExpiredHtlc()** - Core refund logic:
1. Parse HTLC token to extract proofs
2. Verify locktime has expired
3. Verify rider pubkey matches refund tag in secret
4. Sign each proof with rider's wallet key
5. Build swap request with refund witness (signatures + preimage if stored, zeros fallback otherwise)
6. Execute swap at mint
7. Unblind signatures to get plain proofs
8. Return proofs for NIP-60 publishing

### Key Files for Refund
| File | Key Methods |
|------|-------------|
| `PaymentModels.kt:185-215` | `PendingHtlc`, `PendingHtlcStatus` |
| `WalletStorage.kt:262-397` | HTLC storage CRUD |
| `WalletService.kt:141-167` | Auto-refund on connect |
| `WalletService.kt:564-644` | `refundExpiredHtlcs()` |
| `WalletService.kt:674-693` | `findHtlcByPaymentHash()`, `markHtlcClaimedByPaymentHash()` |
| `CashuBackend.kt:639-796` | `refundExpiredHtlc()` |
| `CashuBackend.kt:835-879` | `createHtlcSecretJson()` with refund tag |
| `CashuBackend.kt:903-957` | `extractLocktimeFromSecret()`, `extractRefundKeysFromSecret()` |
| `RiderViewModel.kt:2505-2512` | `handleRideCompletion()` marks HTLC claimed |

---

## Common Issues & Fixes

### "Withdrawal lost my change/remaining balance" (CRITICAL - NUT-05)
**Cause**: `meltWithProofs()` wasn't sending blinded `outputs` for change.
**How it happened**: User withdrew 20000 sats from 27000 sats. Without outputs, mint couldn't return 7000 sats change. Funds LOST to mint.

**The NUT-05 Spec Requirement**:
```
Melt Request: { "quote": str, "inputs": [...], "outputs": [...] }
                                                 ^^^^^^^^^ REQUIRED for change!
```

**The Fix** (`CashuBackend.kt:2331-2504`):
```kotlin
// Calculate expected change
val expectedChange = totalInputAmount - amountNeeded

if (expectedChange > 0) {
    // Create blinded outputs for change (powers of 2)
    val changeAmounts = splitAmount(expectedChange)
    val changePremints = changeAmounts.map { amount ->
        val secret = CashuCrypto.generateSecret()
        val blindingFactor = CashuCrypto.generateBlindingFactor()
        val Y = CashuCrypto.hashToCurve(secret)
        val B_ = CashuCrypto.blindMessage(Y, blindingFactor)
        PreMintSecret(amount, secret, blindingFactor, Y, B_)
    }

    // Include outputs in melt request
    outputsArray = JSONArray()
    changePremints.forEach { pms ->
        outputsArray.put(JSONObject().apply {
            put("amount", pms.amount)
            put("id", keyset.id)
            put("B_", pms.B_)
        })
    }
}

// Unblind returned change signatures
if (json.has("change")) {
    changeArray.forEach { sig ->
        val C = CashuCrypto.unblindSignature(sig.C_, pms.blindingFactor, mintPubKey)
        changeProofs.add(CashuProof(amount, keysetId, pms.secret, C))
    }
}
```

**Key Files**:
- `CashuBackend.kt:2331-2504` - `meltWithProofs()` with change handling
- `CashuBackend.kt:2596-2718` - `recoverDeposit()` (similar pattern for minting)

---

### "Withdrawal/Sync deleted ALL my funds" (CRITICAL - NIP-60 Event Deletion)
**Cause**: One NIP-60 Kind 7375 event can contain MANY proofs. When deleting spent proofs, the code was deleting the ENTIRE EVENT containing all those proofs - including unspent ones.

**How it happened**: User withdrew 20000 sats. The spent proofs shared an event with ~5,000,000 sats of other proofs. Deleting the event deleted ALL 5,000,000 sats.

**The NIP-60 Data Model**:
```
Kind 7375 Event (eventId: "abc123")
├── Proof 1: 1000 sats (spent ✗)
├── Proof 2: 4000 sats (valid ✓)
├── Proof 3: 1000000 sats (valid ✓)
└── Proof 4: 4000000 sats (valid ✓)

Old Code: deleteProofEvents(["abc123"]) → ALL proofs gone!
New Code: Republish proofs 2,3,4 to new event, THEN delete "abc123"
```

**The Fix Pattern** (applied at 6 locations in `WalletService.kt`):
```kotlin
// BEFORE deleting events with spent proofs:
val spentSecrets = spentProofs.map { it.secret }.toSet()
val spentEventIds = spentProofs.map { it.eventId }.distinct()

// Find remaining proofs in those events that are NOT spent
val remainingProofsToRepublish = allProofs.filter { proof ->
    proof.eventId in spentEventIds && proof.secret !in spentSecrets
}

if (remainingProofsToRepublish.isNotEmpty()) {
    val newEventId = sync.publishProofs(remainingProofsToRepublish.map { it.toCashuProof() }, mintUrl)
    if (newEventId == null) {
        // Fallback: store in cdk-kotlin to prevent loss
        cashuBackend.storeRecoveredProofs(cashuProofsToRepublish, mintUrl)
    }
}

// NOW safe to delete - remaining proofs are in new event
sync.deleteProofEvents(spentEventIds)
```

**Fixed Locations**:
| Line (approx) | Function | Context |
|---------------|----------|---------|
| 444 | `syncWallet()` | Current mint spent proof cleanup |
| 560 | `syncWallet()` | Other mint spent proof cleanup |
| 847 | `lockForRide()` | Stale proof cleanup before HTLC |
| 920 | `lockForRide()` | Post-swap spent proof cleanup |
| 1475 | `executeWithdraw()` | Post-melt spent proof cleanup |
| 2305 | `verifyAndRefreshBalance()` | Verification spent proof cleanup |

**Recovery** (if you already lost funds):
1. Go to **Settings → Wallet → Advanced → "Recover Local Funds"**
2. Press **"Sync Wallet"** button
3. cdk-kotlin keeps a local copy of proofs - recovery pulls from there

---

### "Deposit/Change proofs not showing in balance" (NIP-60 Publish)
**Cause**: Proofs minted in cdk-kotlin but NIP-60 publish failed (relay flaky).
**Result**: Proofs stuck in local storage, not in Nostr backup, balance shows wrong.

**The Fix** - Retry logic with persistent fallback (`WalletService.kt`):
```kotlin
// Retry NIP-60 publish up to 3 times
var eventId: String? = null
var attempts = 0
while (eventId == null && attempts < 3) {
    attempts++
    eventId = sync.publishProofs(proofs, mintUrl)
    if (eventId == null && attempts < 3) {
        delay(2000)  // Wait before retry
    }
}

if (eventId == null) {
    // NEW (January 2026): Persistent fallback via RecoveryToken
    saveRecoveryTokenFallback(proofs, mintUrl, "nip60_publish_failed_deposit")
}
```

**Where retry is implemented**:
- `WalletService.kt` - All 9 locations where NIP-60 publish can fail now use `saveRecoveryTokenFallback()`

**Recovery**: Settings → Wallet → "Recover Tokens" (imports saved recovery tokens)

---

### Recovery Token Fallback System (January 2026)

When NIP-60 publish fails after 3 retries, proofs are now saved as **RecoveryToken** to SharedPreferences. This ensures proofs survive app restart.

**The Problem**:
- Old fallback `storeRecoveredProofs()` only stored to cdk-kotlin memory
- If app restarted before "Recover Local Funds" was used, proofs were LOST
- HTTP recovery proofs (deposits, change) were especially vulnerable

**The Solution** - `RecoveryToken` persistence:

```kotlin
// WalletStorage.kt
data class RecoveryToken(
    val id: String,              // UUID for this recovery token
    val token: String,           // cashuA... token string (portable!)
    val amount: Long,            // Total sats in token
    val mintUrl: String,         // Mint URL these proofs are from
    val createdAt: Long,         // When this was saved
    val reason: String           // Why recovery was needed
)

// Persistence methods
fun saveRecoveryToken(token: RecoveryToken)
fun getRecoveryTokens(): List<RecoveryToken>
fun removeRecoveryToken(id: String)
fun getRecoveryTokenCount(): Int
fun getRecoveryTokenTotal(): Long
```

**Fallback Helper** (`WalletService.kt`):
```kotlin
private suspend fun saveRecoveryTokenFallback(proofs: List<CashuProof>, mintUrl: String, reason: String) {
    if (proofs.isEmpty()) return
    // Create cashuA token string (NUT-00 format)
    val tokenStr = "cashuA" + Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP)
    val recoveryToken = RecoveryToken(
        id = UUID.randomUUID().toString(),
        token = tokenStr,
        amount = proofs.sumOf { it.amount },
        mintUrl = mintUrl,
        createdAt = System.currentTimeMillis(),
        reason = reason
    )
    walletStorage.saveRecoveryToken(recoveryToken)
    cashuBackend.storeRecoveredProofs(proofs, mintUrl)  // Also cdk-kotlin backup
}
```

**Recovery Method** (`WalletService.kt`):
```kotlin
suspend fun recoverSavedTokens(): RecoverResult {
    val tokens = walletStorage.getRecoveryTokens()
    tokens.forEach { token ->
        val proofs = decodeTokenToProofs(token.token)
        // Verify proofs are still valid
        val states = cashuBackend.verifyProofStatesBySecret(secrets)
        // Publish valid proofs to NIP-60
        val eventId = sync.publishProofs(validProofs, mintUrl)
        if (eventId != null) {
            walletStorage.removeRecoveryToken(token.id)
        }
    }
    return RecoverResult(recovered, failed, alreadySpent)
}
```

**Reasons Tracked** (for debugging):
| Reason | Where | Trigger |
|--------|-------|---------|
| `nip60_republish_failed_syncwallet` | `syncWallet()` | Republish during spent cleanup |
| `nip60_change_failed_lockforride` | `lockForRide()` | HTLC swap change publish |
| `nip60_change_failed_bridgepayment` | `bridgePayment()` | Cross-mint bridge change |
| `nip60_deposit_failed_executedeposit` | `executeDeposit()` | Deposit proof publish |
| `nip60_change_failed_executewithdraw` | `executeWithdraw()` | Withdrawal change publish |
| `nip60_change_failed_claimhtlc` | `claimHtlcPayment()` | HTLC claim change publish |
| `nip60_refund_failed` | `refundExpiredHtlcs()` | HTLC refund publish |
| `nip60_recover_failed_executedeposit` | HTTP recovery | Deposit recovery publish |
| `nip60_crossmint_failed_mintTokens` | `mintTokens()` | Cross-mint deposit publish |

**Why cashuA Token Format**:
- Standard Cashu token format (NUT-00)
- Can be imported to ANY Cashu wallet (Nutstash, eNuts, etc.)
- Human-readable if needed for manual recovery
- Portable across devices/apps

**Key Files**:
| File | Purpose |
|------|---------|
| `WalletStorage.kt:462-537` | RecoveryToken persistence |
| `WalletService.kt:2450+` | `saveRecoveryTokenFallback()` helper |
| `WalletService.kt:2490+` | `recoverSavedTokens()` method |
| `WalletService.kt:2550+` | `decodeTokenToProofs()` helper |

---

### HTTP Recovery and swapProofsWithMint (January 2026 Fix)

**Background**: When `wallet.mint()` fails (cdk-kotlin), we fall back to HTTP recovery via `recoverDeposit()`. This creates valid proofs via direct HTTP to mint.

**The Bug**: Old code called `swapProofsWithMint()` after HTTP recovery:
```kotlin
// OLD CODE - BAD
val proofs = recoverDeposit(quoteId, amount)
val swappedProofs = swapProofsWithMint(proofs, mintUrl)  // ← PROBLEM
// swappedProofs was never stored! Original proofs gone.
```

**Why It Was Wrong**:
1. `swapProofsWithMint()` sends proofs to mint → gets NEW proofs back
2. But the NEW proofs were never stored or published anywhere
3. Meanwhile, the ORIGINAL proofs from `recoverDeposit()` were now spent
4. Net result: User lost funds

**The Fix**: Remove the swap. HTTP recovery proofs are already fresh from mint:
```kotlin
// NEW CODE - CORRECT
val proofs = recoverDeposit(quoteId, amount)
// Publish directly - proofs are fresh, no swap needed
var eventId = sync.publishProofs(proofs, mintUrl)
if (eventId == null) {
    saveRecoveryTokenFallback(proofs, mintUrl, "nip60_recover_failed_executedeposit")
}
```

**Why Removal is Safe**:
- `recoverDeposit()` gets fresh proofs from mint (via blinding)
- No need to swap fresh proofs - they're already clean
- Swapping was legacy from when we thought proofs needed "freshening"

**Locations Fixed**:
- `WalletService.kt:1330-1365` - `executeDeposit()` HTTP recovery path

---

### "proofs could not be verified" (code 10003)
**Cause**: P2PK key mismatch - rider used Nostr key, driver signs with wallet key.
**Fix**: Rider must use `acceptance.walletPubKey` for HTLC P2PK condition.
**Check**: Log `driverP2pkKey` in `autoConfirmRide()` - should be wallet key, not Nostr key.

### "non-hexadecimal number found at position X"
**Cause**: cdk-kotlin SQLite stores proof `C` field as BLOB (raw bytes), not hex string.
**Fix**: Already implemented in `findColumnValue()`:
```kotlin
android.database.Cursor.FIELD_TYPE_BLOB -> {
    val blob = cursor.getBlob(idx)
    if (blob.size == 33 || name.lowercase() in listOf("c", "signature")) {
        blob.joinToString("") { "%02x".format(it) }  // Hex encode
    }
}
```
**Debug**: Log `First input C:` value - if shows raw bytes like `L@ `, it's the BLOB issue.

### "Hex string must have even length"
**Cause**: Dead code in `hashToCurve()` tried to parse HTLC secret as hex.
**Fix**: HTLC secrets are JSON strings (e.g., `["HTLC",{...}]`), not hex. Hash directly as UTF-8.
**Verification**: Check `CashuCrypto.hashToCurve()` - should NOT have hex parsing.

### NUT-07 verification passes but swap fails with "Token already spent"
**Cause**: `hashToCurve()` was using wrong algorithm - single SHA256 instead of double SHA256.
**Root cause**: NUT-00 specifies **double SHA256**:
1. `msg_hash = SHA256(DOMAIN_SEPARATOR || message)`
2. `final = SHA256(msg_hash || counter)`

Wrong implementation was: `SHA256(domain || message || counter)` (single hash)

**Fix**: Updated `CashuCrypto.hashToCurveBytes()` to use double SHA256:
```kotlin
// NUT-00 specifies DOUBLE SHA256:
val msgHash = sha256(domainBytes + message)  // First SHA256
for (counter in 0 until 65536) {
    val hash = sha256(msgHash + counterBytes)  // Second SHA256
    // ... try to lift x to curve
}
```

**Test vectors** (from cashu reference):
- Input: 32 zero bytes → Y: `024cce997d3b518f739663b757deaec95bcd9473c30a14ac2fd04023a739d1a725`
- Input: 0x00...0001 (31 zeros + 0x01) → Y: `022e7158e11c9506f1aa4248bf531298daa7febd6194f003edcd9b93ade6253acf`

**Location**: `CashuCrypto.kt:68-117`

### "Insufficient NIP-60 proofs" despite balance
**Cause**: cdk-kotlin has proofs not yet synced to NIP-60.
**Fix**: Auto-sync fallback in `lockForRide()`:
```kotlin
if (selection == null && cdkBalance >= amountSats) {
    resyncProofsToNip60()  // Sync cdk proofs to NIP-60
    sync.clearCache()
    selection = sync.selectProofsForSpending(amountSats)  // Retry
}
```

### NumberFormatException on keyset parsing
**Cause**: Some mints return keyset amounts like `2^63` which overflows Long.
**Fix**: Use `toLongOrNull()` in `getActiveKeyset()`:
```kotlin
val amount = amountStr.toLongOrNull()
if (amount != null && amount > 0) { keys[amount] = pubkey }
```

### "Token already spent" (code 11001)
**Cause**: NIP-60 has stale proof events - proofs were spent but NIP-60 events weren't deleted.
**Fix**: `lockForRide()` now verifies proofs with mint (NUT-07) BEFORE swap, and safely deletes:
```kotlin
// Step 1.5: Verify selected proofs with mint (NUT-07)
val stateMap = cashuBackend.verifyProofStatesBySecret(secrets)
if (stateMap != null) {
    val spentSecrets = stateMap.filterValues { it == ProofStateResult.SPENT }.keys
    if (spentSecrets.isNotEmpty()) {
        // SAFE DELETION: Republish remaining proofs FIRST, then delete
        val remainingProofs = allProofs.filter { it.eventId in spentEventIds && it.secret !in spentSecrets }
        if (remainingProofs.isNotEmpty()) {
            sync.publishProofs(remainingProofs.map { it.toCashuProof() }, mintUrl)
        }
        sync.deleteProofEvents(spentEventIds)
        sync.clearCache()
        selection = sync.selectProofsForSpending(amountSats, mintUrl)
    }
}
```
**Location**: `WalletService.kt:817-850`

### "Change proofs invalid after withdrawal" (code 10003 on next spend)
**Cause**: Unblinding used `pms.amount` (our PreMintSecret) to look up the mint's public key instead of the amount from the response.

**How it happened**: We send outputs `[8, 4, 2, 1]` for change. If mint returns signatures in different order or assigns different amounts, we'd use wrong key for unblinding → invalid C value → proof fails verification on next spend.

**The CDK Pattern** (from `dhke.rs`):
```rust
// CDK uses blinded_signature.amount from RESPONSE
let a: PublicKey = keys.amount_key(blinded_signature.amount)
```

**The Fix** (all unblinding locations in `CashuBackend.kt`):
```kotlin
// Read amount from mint's response, not from our premint
val responseAmount = sig.getLong("amount")

// Use response amount for key lookup
val mintPubKey = keyset.keys[responseAmount]

// Store response amount in the proof
changeProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
```

**Fixed in 8 locations**:
- Line 471, 653 - HTLC claim
- Line 837 - HTLC refund
- Line 1287, 1308 - HTLC token creation
- Line 2539 - Melt change (withdrawals)
- Line 2867 - Deposit recovery
- Line 3043 - Swap

**Key insight**: Only `pms.secret` and `pms.blindingFactor` (r) must come from our PreMintSecret. Everything else (amount, keyset ID, C_) should come from the mint's response.

### "Balance shows wrong amount"
1. Go to **Settings → Wallet → Sync Wallet**
2. `syncWallet()` verifies all NIP-60 proofs with mint, auto-migrates URLs if needed
3. Check diagnostics for any remaining issues (Settings → Wallet → show diagnostics)

### "Deposit paid but no balance"
1. Check `pendingDeposits` in WalletStorage
2. Pull-to-refresh triggers recovery
3. Manual: `recoverDeposit(quoteId, amount)`

### "LN address doesn't work"
- `WalletService.resolveLnAddress()` at line 600-604 is broken
- Users must paste BOLT11 invoice directly
- Fix requires amount-first UI flow (enter amount → query LNURL → get invoice)

### "Wallet shows Set Up for existing npub"
- `hasExistingNip60Wallet()` not detecting existing wallet on import
- Kind 17375 metadata may not be saving/restoring mint URL properly

### "Refund fails with already spent"
**Cause**: Driver already claimed the HTLC, but rider's status wasn't updated.
**Fix**: `handleRideCompletion()` now calls `markHtlcClaimedByPaymentHash()`.
**If it still fails**: The status is marked FAILED (stops retries). This is expected - funds went to driver.

---

## Debugging Checklist

### Safe NIP-60 Event Deletion (CRITICAL)
**NEVER call `deleteProofEvents()` without first republishing remaining proofs!**

One NIP-60 event can contain many proofs. The pattern:
```kotlin
// 1. Find remaining proofs in affected events that are NOT spent
val remainingProofs = allProofs.filter { proof ->
    proof.eventId in eventIdsToDelete && proof.secret !in spentSecrets
}

// 2. Republish remaining proofs to a NEW event
if (remainingProofs.isNotEmpty()) {
    val newEventId = sync.publishProofs(remainingProofs.map { it.toCashuProof() }, mintUrl)
    if (newEventId == null) {
        // Fallback: store in cdk-kotlin to prevent loss
        cashuBackend.storeRecoveredProofs(remainingProofs, mintUrl)
    }
}

// 3. NOW safe to delete the old events
sync.deleteProofEvents(eventIdsToDelete)
```

### HTLC Claim Failures
1. **Check key match**: Is `acceptance.walletPubKey` used for P2PK?
2. **Check C field encoding**: Is it hex (66 chars) or raw bytes?
3. **Check signature**: Log `signP2pkProof()` output - should be 128-char hex
4. **Check preimage**: Does `verifyPreimage(preimage, paymentHash)` return true?

### HTLC Refund Failures
1. **Check locktime**: Is `now > locktime`? (must be expired)
2. **Check rider pubkey**: Does it match the `refund` tag in secret?
3. **Check status**: Is HTLC still LOCKED? (not CLAIMED/REFUNDED/FAILED)
4. **Check mint response**: Code 11001 = already claimed by driver

### Balance Discrepancies
```kotlin
// Compare all sources
cashuBackend.getCdkBalance()      // SQLite proofs
nip60Sync.getBalance()            // Nostr proofs
cashuBackend.getVerifiedBalance() // NUT-07 verified
walletStorage.getCachedBalance()  // Last known
```

### Mint API Errors
```kotlin
// Add logging before swap
Log.d(TAG, "Swap request: ${requestBody.toString(2)}")
Log.d(TAG, "First input C: ${inputs.getJSONObject(0).getString("C")}")
Log.d(TAG, "Swap response: ${response.code}")
```

---

## File Quick Reference

| File | Purpose | Key Methods |
|------|---------|-------------|
| `CashuBackend.kt` | Mint operations, P2PK signing | `claimHtlcToken()`, `refundExpiredHtlc()`, `signP2pkProof()`, `createHtlcTokenFromProofs()` |
| `WalletKeyManager.kt` | Wallet keypair + signing | `signSchnorr()`, `getWalletPubKeyHex()` |
| `WalletService.kt` | Orchestration, public API | `syncWallet()`, `lockForRide()`, `claimHtlcPayment()`, `recoverSavedTokens()`, `saveRecoveryTokenFallback()` |
| `WalletStorage.kt` | Local encrypted storage | `savePendingHtlc()`, `getRefundableHtlcs()`, `updateHtlcStatus()`, `saveRecoveryToken()`, `getRecoveryTokens()` |
| `Nip60WalletSync.kt` | Nostr sync (Kind 7375/17375) | `publishProofs()`, `restoreFromNostr()`, `selectProofsForSpending()`, `republishProofsWithNewMint()` |
| `CashuCrypto.kt` | secp256k1 crypto, NUT-00 hash_to_curve | `hashToCurve()`, `hashToCurveBytes()`, `blindMessage()`, `unblindSignature()` |
| `PaymentModels.kt` | Data classes | `PendingHtlc`, `PendingHtlcStatus`, `EscrowLock`, `RecoverResult` |
| `WalletSettingsScreen.kt` | Wallet management UI | Sync, change mint, diagnostics |

**Deprecated** (UI removed, internal only):
- `verifyAndRefreshBalance()` - Use `syncWallet()` instead
- `resyncProofsToNip60()` - Used by `lockForRide()` fallback only
- `storeRecoveredProofs()` - Still called internally but `saveRecoveryTokenFallback()` is the primary fallback

---

## Key Line References

| Location | What |
|----------|------|
| **Recovery Token Fallback (NEW)** | |
| `WalletStorage.kt:462-537` | RecoveryToken data class + persistence |
| `WalletService.kt:2450+` | `saveRecoveryTokenFallback()` helper |
| `WalletService.kt:2490+` | `recoverSavedTokens()` method |
| `WalletService.kt:2550+` | `decodeTokenToProofs()` helper |
| **Cross-Mint Bridge** | |
| `RiderViewModel.kt:2443-2456` | `executeBridgePayment()` with auto-cancel on failure |
| **NUT-05 Melt (Withdrawals)** | |
| `CashuBackend.kt:2331-2504` | `meltWithProofs()` with change outputs (CRITICAL) |
| `CashuBackend.kt:2366-2407` | Change blinding (outputs creation) |
| `CashuBackend.kt:2532-2588` | Change unblinding - uses response amount (CRITICAL) |
| **Unblinding (All Locations)** | Uses `sig.getLong("amount")` for key lookup |
| `CashuBackend.kt:471` | HTLC claim unblinding |
| `CashuBackend.kt:653` | HTLC claim with proofs unblinding |
| `CashuBackend.kt:837` | HTLC refund unblinding |
| `CashuBackend.kt:1287,1308` | HTLC token creation unblinding |
| `CashuBackend.kt:2867` | Deposit recovery unblinding |
| `CashuBackend.kt:3043` | Swap unblinding |
| **NIP-60 Safe Deletion (CRITICAL)** | |
| `WalletService.kt:444-468` | `syncWallet()` - current mint spent proof cleanup |
| `WalletService.kt:540-562` | `syncWallet()` - other mint spent proof cleanup |
| `WalletService.kt:826-848` | `lockForRide()` - stale proof cleanup |
| `WalletService.kt:900-930` | `lockForRide()` - post-swap cleanup |
| `WalletService.kt:1455-1490` | `executeWithdraw()` - post-melt cleanup |
| `WalletService.kt:2285-2310` | `verifyAndRefreshBalance()` - verification cleanup |
| **NIP-60 Publish Retry** | |
| `WalletService.kt:1285-1310` | Deposit proof publish retry |
| `WalletService.kt:1413-1443` | Withdrawal change publish retry |
| `WalletService.kt:875-900` | HTLC lock change publish retry |
| **Sync & Balance** | |
| `WalletService.kt:382` | `syncWallet()` - THE sync function |
| `WalletService.kt:285` | `changeMintUrl()` - uses syncWallet() |
| `WalletService.kt:293` | NIP-60 auto-sync fallback |
| `WalletService.kt:765` | NUT-07 verification before HTLC swap |
| **HTLC Escrow** | |
| `RiderViewModel.kt:1843` | HTLC locking in `autoConfirmRide()` |
| `RiderViewModel.kt:2505-2512` | Mark HTLC claimed in `handleRideCompletion()` |
| `DriverViewModel.kt:1031` | Wallet pubkey in `acceptOffer()` |
| `DriverViewModel.kt:2810` | Wallet pubkey in `acceptBroadcastRequest()` |
| `DriverViewModel.kt:1621` | HTLC claim in `completeRide()` |
| `WalletService.kt:141-167` | Auto-refund check on `connect()` |
| `WalletService.kt:439-448` | Save PendingHtlc in `lockForRide()` |
| `WalletService.kt:564-644` | `refundExpiredHtlcs()` |
| `WalletService.kt:674-693` | `findHtlcByPaymentHash()`, `markHtlcClaimedByPaymentHash()` |
| **Crypto & Signing** | |
| `CashuBackend.kt:630` | `signP2pkProof()` helper |
| `CashuBackend.kt:639-796` | `refundExpiredHtlc()` |
| `CashuBackend.kt:835-879` | `createHtlcSecretJson()` with locktime/refund tags |
| `CashuBackend.kt:903-957` | `extractLocktimeFromSecret()`, `extractRefundKeysFromSecret()` |
| `CashuBackend.kt:1044` | `verifyProofStatesBySecret()` helper |
| `CashuBackend.kt:1585` | BLOB hex encoding fix |
| `CashuBackend.kt:2596-2718` | `recoverDeposit()` - custom minting with unblinding |
| `CashuCrypto.kt:68` | `hashToCurveBytes()` - NUT-00 double SHA256 |
| `WalletKeyManager.kt:308` | `signSchnorr()` implementation |

---

## NIP-60 Proof Selection

Spending uses NIP-60 proofs directly (cross-device compatible):
```kotlin
// In lockForRide()
val selection = nip60Sync.selectProofsForSpending(amountSats, mintUrl)
// If insufficient, lockForRide() has internal fallback (rarely triggered)
```

**Note**: The old `resyncProofsToNip60()` fallback is deprecated - `lockForRide()` still calls it internally for edge cases where cdk-kotlin has proofs that NIP-60 doesn't, but this is rare.

---

## Balance Architecture (Source of Truth)

**NIP-60 IS the wallet**. cdk-kotlin is NOT a balance source - it's only for mint API calls.

| Source | Purpose |
|--------|---------|
| NUT-07 Verified via `syncWallet()` | Authoritative - what mint says |
| NIP-60 Nostr (Kind 7375) | The wallet - cross-device sync |
| Cached balance | UI fallback until sync completes |

**Note**: `getCdkBalance()` and `getVerifiedBalance()` are deprecated diagnostics - no longer shown in UI.

---

## Dual-Origin Debugging Principle

When debugging payment issues, **trace at BOTH**:
1. **ORIGINATION**: What key is rider using for P2PK? What data is sent to mint?
2. **RECEIVER**: What key is driver signing with? What does mint expect?

The wallet pubkey mismatch was only discovered by examining BOTH rider (locking) and driver (claiming) code paths.

---

## Change Mint URL

When mint URL changes (e.g., cloudflare tunnel restart) but it's the same mint:

### Usage
1. Go to **Settings → Wallet**
2. Find **Change Mint** section
3. Enter new URL in dialog
4. Confirm change

### What It Does
```kotlin
// WalletService.changeMintUrl() - simplified
1. Disconnect from old mint
2. Connect to new mint URL
3. Call syncWallet() - handles ALL verification + URL migration
```

`syncWallet()` internally:
- Fetches ALL NIP-60 proofs (regardless of stored mint URL)
- Verifies with new mint (NUT-07)
- Auto-migrates proof URLs via `republishProofsWithNewMint()`
- Cleans up any spent proofs
- Updates displayed balance

### Key Files
| Location | What |
|----------|------|
| `WalletService.kt:285` | `changeMintUrl()` implementation |
| `WalletSettingsScreen.kt` | UI for mint management |

### Warning
- Only use when mint URL changed but mint is the same
- If new mint doesn't recognize proofs, funds may be lost
- `syncWallet()` will show verified balance (or 0 if proofs invalid)

---

## Cross-Mint Bridge Payment Handling

### Auto-Cancel on Bridge Failure (January 2026)

When cross-mint bridge payment fails, the ride is now auto-cancelled with a clear message.

**Previous Behavior** (problematic):
- Bridge payment failed silently
- Ride continued in broken state (no payment)
- Driver stuck waiting, rider confused

**Current Behavior** (fixed in `RiderViewModel.kt`):
```kotlin
// In executeBridgePayment()
} else {
    Log.e(TAG, "Bridge payment failed: ${result?.error} - auto-cancelling ride")
    _uiState.value = _uiState.value.copy(
        bridgeInProgress = false,
        error = "Payment failed: ${result?.error ?: "Unknown error"}. Ride cancelled."
    )
    // Auto-cancel the ride since payment failed
    clearRide()
}
```

**User Experience**:
1. Rider sees clear error: "Payment failed: [reason]. Ride cancelled."
2. Ride is cancelled on rider side
3. Driver receives cancellation and can accept other rides

**Key File**: `RiderViewModel.kt:2443-2456` - `executeBridgePayment()`

---

## Known Limitations

1. **LN Address Resolution** - `resolveLnAddress()` broken, users must paste BOLT11
2. **Wallet UI on Fresh Install** - May show "Set Up" for existing npubs
3. **Single Mint** - Only one mint supported currently (see [Issue #15](https://github.com/variablefate/ridestr/issues/15) for multi-mint plan)
4. **Escrow Bypass** - Rides proceed if escrow lock fails (needs enforcement)

---

## NUT Spec Compliance Audit (January 2026)

### Currently Implemented

| NUT | Status | Notes |
|-----|--------|-------|
| NUT-00 (Crypto) | ✅ | Double SHA256 hashToCurve, point arithmetic |
| NUT-01 (Keys) | ✅ | `/v1/keys` endpoint |
| NUT-02 (Keysets) | ✅ | `/v1/keysets`, fee calculation |
| NUT-03 (Swap) | ✅ | `/v1/swap` - core operation |
| NUT-04 (Mint) | ✅ | Via cdk-kotlin `wallet.mint()` + custom `recoverDeposit()` fallback |
| NUT-05 (Melt) | ✅ | Custom `meltWithProofs()` - change outputs included |
| NUT-06 (Info) | ✅ | Capability checking |
| NUT-07 (State) | ✅ | Custom `verifyProofStatesBySecret()` |
| NUT-08 (Overpaid) | ✅ | Change handling in melt |
| NUT-09 (Restore) | ✅ | `/v1/restore` - seed recovery |
| NUT-10 (Conditions) | ✅ | HTLC secret JSON format |
| NUT-11 (P2PK) | ✅ | Per-proof Schnorr signatures |
| NUT-13 (Deterministic) | ✅ | Seed-based secret derivation |
| NUT-14 (HTLC) | ✅ | Custom create/claim/refund with witness |

**Audit triggered by**: User lost ~7000 sats in withdrawal due to missing change outputs.
**Root cause**: `meltWithProofs()` sent inputs without outputs - mint couldn't return change.

---

## Planned NUT Implementations

The following NUTs are planned for implementation. See GitHub issues for details.

### NUT-17: WebSocket Subscriptions ✅ IMPLEMENTED ([Issue #28](https://github.com/variablefate/ridestr/issues/28))

**Status:** ✅ COMPLETE (January 2026)

Real-time state updates via WebSocket for melt and mint quotes. Falls back to polling if mint doesn't support NUT-17.

**Files Added:**
- `CashuWebSocketModels.kt` - JSON-RPC 2.0 data classes, state enums, payload parsers
- `CashuWebSocket.kt` - Connection management, subscription handling, reconnection

**CashuBackend.kt Changes:**
- `MintCapabilities.supportsWebSocket` and `.webSocketCommands` - NUT-17 capability detection
- `waitForMeltQuoteState()` - WebSocket subscription with polling fallback
- `waitForMintQuoteState()` - WebSocket subscription with polling fallback
- WebSocket lifecycle in `connect()` / `disconnect()`

**Usage:**
```kotlin
// Automatic - waitForMeltQuoteState tries WebSocket first, falls back to polling
val quote = cashuBackend.waitForMeltQuoteState(quoteId, timeoutMs = 60_000L)

// Check capabilities
if (cashuBackend.supportsWebSocketKind(SubscriptionKind.BOLT11_MELT_QUOTE)) {
    // Mint supports NUT-17 for melt quotes
}
```

**Message Format:**
```json
// Subscribe
{"jsonrpc":"2.0","id":1,"method":"subscribe","params":{"kind":"bolt11_mint_quote","filters":["quote_id"],"subId":"uuid"}}

// Notification
{"jsonrpc":"2.0","method":"subscribe","params":{"subId":"uuid","payload":{"quote":"...","state":"PAID"}}}
```

### NUT-12: DLEQ Proofs ([Issue #29](https://github.com/variablefate/ridestr/issues/29))

**Priority: MEDIUM** - Mint signature verification without trust.

**Problem**: Currently trust mint's blind signatures without verification. Invalid signatures could result in non-spendable proofs.

**Solution**: Verify DLEQ (Discrete Log Equality) proofs that demonstrate the mint used the same private key for both its public key and signing.

**Key Changes:**
- Add `CashuCrypto.verifyDleq(A, B_, C_, e, s)` function
- Add verification at all 7 unblinding locations in `CashuBackend.kt`
- Uses existing ACINQ secp256k1 library

**Verification:**
```
R1 = s*G - e*A
R2 = s*B' - e*C'
Verify: e == SHA256(R1 || R2 || A || C')
```

### NUT-21/22: Mint Authentication ([Issue #30](https://github.com/variablefate/ridestr/issues/30))

**Priority: MEDIUM** - Access authenticated/premium mints.

**Flow:**
1. **NUT-21 (Clear Auth)**: OAuth 2.0 login → Clear Auth Token (CAT)
2. **NUT-22 (Blind Auth)**: Exchange CAT for Blind Auth Tokens (BATs)
3. BATs used as single-use auth for protected endpoints

**Key Changes:**
- New `MintAuthManager.kt` for OAuth flow
- BAT ecash management (special keyset with `unit=auth`)
- Request interceptor for `Blind-auth` header
- UI for login/logout in wallet settings

**Headers:**
- `Clear-auth: <CAT>` - For BAT minting
- `Blind-auth: authA<base64>` - For protected endpoints

**Dependency**: Requires NUT-12 (DLEQ) for BAT verification.

### NUT-20: Signed Mint Quotes ([Issue #31](https://github.com/variablefate/ridestr/issues/31))

**Priority: LOW** - MITM protection for mint outputs.

**Problem**: Attacker could intercept mint request and replace outputs with their own.

**Solution**: Sign the quote ID + blinded outputs with BIP-340 Schnorr.

**Key Changes:**
- Generate ephemeral keypair for each quote
- Add `pubkey` to mint quote request
- Add `signature` to mint request
- Store quote keypairs in `WalletStorage`

**Message to Sign:**
```
msg = UTF-8(quote_id) || hex(B_0) || hex(B_1) || ... || hex(B_n)
signature = BIP340_Schnorr(SHA256(msg), ephemeral_key)
```

**Note**: Uses same signing infrastructure as HTLC claims (`WalletKeyManager.signSchnorr`).

---

## Additional Resources

- [PAYMENT_ARCHITECTURE.md](../../../docs/architecture/PAYMENT_ARCHITECTURE.md) - Full payment system docs
- [CLAUDE.md](../../CLAUDE.md) - Main project instructions with payment integration details
