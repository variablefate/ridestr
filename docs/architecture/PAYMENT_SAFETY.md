# Payment Safety Checklist

**Last Updated**: 2026-02-02
**Purpose**: Required verification steps before modifying payment code.

---

## Overview

Payment code handles real user funds. Changes require extra scrutiny.

---

## Pre-Modification Checklist

Before changing ANY code in these files:

- `WalletService.kt`
- `CashuBackend.kt`
- `WalletKeyManager.kt`
- `Nip60WalletSync.kt`
- `PaymentCrypto.kt`

### 1. Understand the Change Scope

- [ ] Identify ALL methods affected (direct and called-by)
- [ ] Read region comments in the file to understand sequencing
- [ ] Check if change affects atomicity guarantees

### 2. Verify Test Coverage

- [ ] Run existing tests: `./gradlew :common:testDebugUnitTest`
- [ ] Add new tests if changing pure functions
- [ ] Consider edge cases: empty balance, stale proofs, network failure

### 3. Document Expected Behavior

- [ ] Write down what should happen in success case
- [ ] Write down what should happen in each failure case
- [ ] Compare with current behavior (read the code)

---

## HTLC Flow Invariants

### lockForRide() - MUST Maintain These Steps

```
1. selectProofsForSpending() - Get proofs from NIP-60
   └── INVARIANT: Must have enough balance

2. verifyProofStatesBySecret() - NUT-07 check
   └── INVARIANT: Verify BEFORE any swap
   └── INVARIANT: If SPENT found, delete and retry

3. savePendingBlindedOperation() - Crash recovery
   └── INVARIANT: Save BEFORE mint call

4. createHtlcTokenFromProofs() - Call mint
   └── INVARIANT: Use driver's wallet pubkey (not Nostr pubkey)

5. On Success:
   └── INVARIANT: Clear pendingOpId ONLY after NIP-60 publish
   └── INVARIANT: Publish change proofs to NIP-60

6. On Failure:
   └── INVARIANT: Keep pending op for recovery
   └── INVARIANT: Return null (caller handles gracefully)
```

### claimHtlcPayment() - MUST Maintain These Steps

```
1. parseHtlcToken() - Decode token to proofs
   └── INVARIANT: Return null if parse fails (don't throw)

2. signP2pkProof() for each proof - BIP-340 Schnorr
   └── INVARIANT: Sign with wallet key (not Nostr key)

3. claimHtlcTokenWithProofs() - Submit to mint
   └── INVARIANT: Include preimage in witness
   └── INVARIANT: Include per-proof signatures

4. On Success:
   └── INVARIANT: Publish claimed proofs to NIP-60
   └── INVARIANT: Log with correlation ID

5. On Failure:
   └── INVARIANT: Token still valid (can retry)
   └── INVARIANT: Don't delete original proofs
```

### refundExpiredHtlc() - MUST Maintain These Steps

```
1. parseHtlcToken() - Decode token
2. extractLocktimeFromSecret() - Get locktime
   └── INVARIANT: Verify current time > locktime

3. Sign with refund key
   └── INVARIANT: Use preimage from PendingHtlc (or zeros fallback)

4. Submit refund claim to mint
5. On Success: publish refunded proofs to NIP-60
```

---

## Dangerous Operations

### DO NOT Do These Without Review

1. **Delete proofs without verification**
   - Always verify state with NUT-07 before deletion
   - Stale NIP-60 events are safer than lost funds

2. **Modify blinding factor generation**
   - NUT-13 derivation is deterministic for recovery
   - Wrong blinding = invalid proofs = lost funds

3. **Change signature format**
   - NUT-11/14 P2PK signatures must be exact
   - Wrong format = claim rejected = funds stuck

4. **Skip pending operation save**
   - Crash during swap loses track of in-flight proofs
   - Pending op enables recovery

5. **Force overwrite NIP-60 metadata**
   - Other apps may use same Nostr key
   - Use `forceOverwrite` only with user confirmation

---

## Testing Requirements

### After Payment Code Changes

1. **Unit Tests** (required):
   ```bash
   ./gradlew :common:testDebugUnitTest
   ```

2. **Manual Flow Test** (required for HTLC changes):
   - Create ride offer
   - Accept on driver
   - Verify HTLC locked (check rider logs)
   - Complete ride
   - Verify HTLC claimed (check driver logs)
   - Verify balances updated both sides

3. **Failure Recovery Test** (for atomicity changes):
   - Kill app during lockForRide()
   - Restart app
   - Verify pending op recovery works
   - Verify no duplicate proofs

4. **Stale Proof Test** (for NIP-60 changes):
   - Have another client spend proofs
   - Try to lock HTLC
   - Verify NUT-07 catches stale proofs
   - Verify auto-cleanup and retry

---

## Correlation ID Verification

After payment code changes, verify correlation IDs appear:

```
# Rider during lockForRide:
[RIDE xxxxxxxx] Locking HTLC: fareAmount=100, paymentHash=abcd...

# Driver during claimHtlcPayment:
[RIDE yyyyyyyy] Claiming HTLC: paymentHash=abcd...
```

If correlation IDs are missing, the change may have bypassed the ViewModel integration points.

---

## Emergency Recovery Procedures

### If User Reports Lost Funds

1. **Check NIP-60 for proof events**
   - Kind 7375 events contain proofs
   - May be on relay even if app doesn't see them

2. **Check mint for proof states**
   - NUT-07 `/v1/checkstate` shows if proofs spent
   - If UNSPENT, proofs can be recovered

3. **Check for pending operations**
   - `WalletStorage.getPendingBlindedOperations()`
   - May contain outputs from interrupted swap

4. **NUT-09 restore as last resort**
   - `WalletService.recoverFromSeed()` scans mint
   - Only works if user has mnemonic

---

## Code Review Checklist

When reviewing payment PRs:

- [ ] All INVARIANTS maintained (see above)
- [ ] No new Android dependencies in pure functions
- [ ] Correlation ID logging present
- [ ] Error handling doesn't swallow exceptions silently
- [ ] NIP-60 publish happens AFTER success, not before
- [ ] Tests added for new code paths
- [ ] Manual testing documented in PR
