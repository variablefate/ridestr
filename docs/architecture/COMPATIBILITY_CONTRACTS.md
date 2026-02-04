# Compatibility Contracts

**Last Updated**: 2026-02-03
**Purpose**: Document API stability requirements and architectural contracts that must be preserved during refactoring.

---

## Overview

This document codifies contracts that must be maintained for backward compatibility. Breaking these contracts causes runtime failures in production.

---

## NostrService Facade Contracts

### userDisplayName Must Stay on Facade

**Contract**: `NostrService.userDisplayName` StateFlow must be a delegating property that returns the SAME StateFlow instance from the underlying domain service.

**Reason**: Compose's `collectAsState()` caches the StateFlow reference. If the facade returns a different StateFlow on each access, UI updates break silently.

**Call Sites That Depend on This**:
- `rider-app/MainActivity.kt:783,958` - `nostrService.userDisplayName.collectAsState()`
- `drivestr/MainActivity.kt:1261` - `nostrService.userDisplayName.collectAsState()`
- `rider-app/MainActivity.kt:404` - `nostrService.fetchAndCacheUserDisplayName()`
- `drivestr/MainActivity.kt:299,515` - `nostrService.fetchAndCacheUserDisplayName()`

**Implementation**:
```kotlin
// NostrService.kt - REQUIRED pattern
val userDisplayName: StateFlow<String>
    get() = profileBackupService.userDisplayName  // Returns SAME instance

// NOT this - would break Compose:
val userDisplayName = profileBackupService.userDisplayName.value  // WRONG
```

---

## Domain Service Instance Sharing

### RelayManager/KeyManager Must Be Shared

**Contract**: All domain services extracted from NostrService MUST receive the same `RelayManager` and `KeyManager` instances via constructor injection.

**Reason**: Multiple RelayManager instances would:
1. Create duplicate relay connections
2. Cause subscription ID collisions
3. Waste battery and bandwidth
4. Break subscription cleanup coordination

**Correct Pattern**:
```kotlin
class NostrService(context: Context, relays: List<String>) {
    val keyManager = KeyManager(context)
    val relayManager = RelayManager(relays)

    // Domain services share instances
    private val cryptoHelper = NostrCryptoHelper(keyManager)
    private val profileBackupService = ProfileBackupService(relayManager, keyManager)
    private val roadflareDomainService = RoadflareDomainService(relayManager, keyManager)
}
```

**Known Exceptions**:
- `RoadflareListenerService.kt:308` - Has its own NostrService lifecycle
- `RemoteConfigManager.kt:111` - Pre-dates domain service pattern

---

## Payment Atomicity Contracts

### Escrow Sequencing Invariants

**Contract**: HTLC operations must follow strict sequencing to prevent fund loss.

**lockForRide() Sequence**:
1. Verify proof states with NUT-07 BEFORE any swap
2. Save `PendingBlindedOperation` BEFORE mint call
3. Call mint `/v1/swap` endpoint
4. If success: clear pending, publish NIP-60, return token
5. If failure: pending op allows recovery

**claimHtlcPayment() Sequence**:
1. Parse token to extract proofs
2. Sign each proof with P2PK (BIP-340 Schnorr)
3. Submit to mint with preimage + signatures
4. If success: publish claimed proofs to NIP-60
5. If failure: proofs remain on token (can retry)

**refundExpiredHtlc() Sequence**:
1. Verify locktime has passed
2. Use stored preimage (or zeros for old HTLCs)
3. Sign with refund key
4. Submit to mint

**Location**: See region comments in `WalletService.kt` (HTLC PAYMENT FLOWS section)

---

## Subscription Cleanup Contract

### closeSubscription() Pattern

**Contract**: Subscriptions must be closed through the same RelayManager that created them.

**Primary Pattern**:
```kotlin
// Domain services call through their relayManager reference
relayManager.closeSubscription(subscriptionId)
```

**Allowed Exception**:
```kotlin
// Direct access when RelayManager reference already held
nostrService?.relayManager?.closeSubscription(id)  // RoadflareListenerService
```

**Rationale**: RelayConnection tracks active subscriptions per connection. Closing through wrong manager leaves stale state.

---

## Event Parser expectedPubKey Contract

**Contract**: All ride event parsers with signature validation MUST validate the `pubkey` field matches the expected participant.

**Parsers with expectedPubKey**:
- `RideAcceptanceEvent.parse(event, expectedDriverPubKey)`
- `RideConfirmationEvent.parseEncrypted(event, expectedRiderPubKey)`
- `DriverRideStateEvent.parse(event, expectedDriverPubKey)`
- `RiderRideStateEvent.parse(event, expectedRiderPubKey)`

**Reason**: Without pubkey validation, an attacker could:
1. Intercept relay messages
2. Forge events with correct structure but wrong signature
3. Trigger state changes (e.g., fake cancellations)

The signature (`event.verify()`) only proves the event was signed by `event.pubkey`. The expectedPubKey check proves it was the CORRECT participant.

---

## Token Encoding Contract

### CashuTokenCodec is Stateless

**Contract**: All methods in `CashuTokenCodec` must remain stateless pure functions.

**Reason**: Token encoding/decoding is called from multiple contexts (CashuBackend, WalletService, tests). Stateful operations would introduce subtle bugs.

**Functions That Must Stay Stateless**:
- `encodeHtlcProofsAsToken(proofs, mintUrl)` → String
- `encodeProofsAsToken(proofs, mintUrl)` → String
- `parseHtlcToken(token)` → Pair<List<HtlcProof>, String>?
- `extractPaymentHashFromSecret(secret)` → String?
- `extractLocktimeFromSecret(secret)` → Long?
- `extractRefundKeysFromSecret(secret)` → List<String>

---

## Testing Contracts

### Unit Test Independence

**Contract**: Unit tests must not depend on Android runtime except through Robolectric.

**Files That Require Robolectric**:
- `CashuTokenCodecTest.kt` - Uses `android.util.Base64`
- `CashuCryptoTest.kt` - Uses `android.util.Log`
- `CashuBackendErrorTest.kt` - Uses ApplicationProvider context

**Files That Don't Need Robolectric**:
- `PaymentCryptoTest.kt` - Pure Java crypto only
- `HtlcResultTest.kt` - Pure sealed class tests
- `FakeMintApiTest.kt` - Queue behavior tests

**Configuration**:
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
```

### CashuBackend Test State Injection

**Contract**: Test state injection via `setTestState()` must bypass ALL HTTP calls.

**Fields Set by setTestState()**:
- `currentMintUrl` - Bypasses `connect()` HTTP to `/v1/info`
- `testActiveKeyset` - Bypasses `getActiveKeyset()` HTTP to `/v1/keysets`, `/v1/keys`
- `walletSeed` - Bypasses mnemonic derivation

**Why This Exists**: HTLC methods have early-exit guards that check `currentMintUrl` and `getActiveKeyset()`. Without state injection, tests would need a real or mock mint server.

### FakeMintApi Queue Contract

**Contract**: `FakeMintApi` queues are FIFO - responses are returned in the order they were queued.

**Methods**:
- `queueSwapSuccess(signatures)` - Queue successful swap response
- `queueSwapHttpError(code, body)` - Queue HTTP error
- `queueSwapTransportFailure(message)` - Queue network failure
- `queueCheckstateSuccess(states)` - Queue checkstate response

**Usage**:
```kotlin
fakeMintApi.queueSwapHttpError(400, "Token already spent")
val result = backend.createHtlcTokenFromProofs(...)  // Returns SwapRejected
```

---

## Version Compatibility

### Backward-Compatible Event Fields

**Contract**: New optional fields added to Nostr events must not break old clients.

**Pattern**:
```kotlin
// Parser must handle missing fields gracefully
val walletPubKey = obj.optString("wallet_pubkey", null)  // Optional
val paymentHash = obj.getString("payment_hash")  // Required - old events have this
```

**Events With Optional Fields**:
- Kind 3173 (Offer): `payment_method` optional (defaults to CASHU)
- Kind 3174 (Acceptance): `wallet_pubkey` optional (falls back to `driverPubKey`)
- Kind 3175 (Confirmation): `payment_hash`, `escrow_token` added January 2026

---

## Breaking Changes Checklist

When modifying code covered by these contracts:

- [ ] Check all call sites listed in the contract
- [ ] Run full test suite (`./gradlew :common:testDebugUnitTest`)
- [ ] Manual test on device: offer → accept → confirm → complete
- [ ] Test cross-device: rider on one device, driver on another
- [ ] Verify Compose UI updates correctly (userDisplayName, balance, etc.)
