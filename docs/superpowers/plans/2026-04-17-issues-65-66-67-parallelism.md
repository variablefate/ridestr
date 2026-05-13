# Issues #65 / #66 / #67 — Parallelism Analysis & Execution Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Determine whether #65 (RiderViewModel decomposition), #66 (DriverViewModel decomposition), and #67 (screen component extraction) can run as independent parallel worktree sessions with separate PRs, and define the merge order.

**Architecture:** Three refactors touch three distinct module boundaries (rider-app, drivestr, roadflare-rider + :common coordinators). File-level overlap is near-zero with one identified conditional hazard on `roadflare-rider/RideTab.kt`. All three sessions can open draft PRs immediately.

**Tech Stack:** Kotlin, Jetpack Compose, :common Android library module, GitNexus knowledge graph.

---

## Data Sources

All file-level claims below are backed by:
- `gh issue view 65 66 67` — issue bodies read verbatim
- `gitnexus impact(RiderViewModel, direction=upstream)` and `impact(DriverViewModel, direction=upstream)`
- `gitnexus context` on screen symbols via hook results
- Direct grep/read of import blocks in each screen file
- `wc -l` for file sizes to sanity-check scope

---

## Part 1: Files Each Issue Will Touch

### Issue #65 — RiderViewModel Decomposition

**Files modified:**

| File | Change |
|------|--------|
| `rider-app/src/main/java/com/ridestr/rider/viewmodels/RiderViewModel.kt` | Major: extract offer/payment/roadflare logic into coordinator calls; ViewModel becomes thin state-composition layer. `RiderUiState`, `RiderRideSession`, `RideStage` stay in this file (per issue: "ViewModel keeps UI-state composition"). |
| `rider-app/src/main/java/com/ridestr/rider/MainActivity.kt` | Minor: construct/inject the three new coordinators. |
| `roadflare-rider/src/main/java/com/roadflare/rider/viewmodels/RiderViewModel.kt` | Step 4: migrate to consume `:common` coordinators (394 lines; lighter lift than rider-app). |
| `roadflare-rider/src/main/java/com/roadflare/rider/viewmodels/RideSessionManager.kt` | Adapt or replace: currently owns `RideSession`, `DriverInfo`, `ChatMessage` data classes + session lifecycle. Step 4 decision: keep as adapter or delete in favour of `:common` equivalents. |
| `roadflare-rider/src/main/java/com/roadflare/rider/viewmodels/ChatCoordinator.kt` | Adapt or replace: delegates to `OfferCoordinator` or `RoadflareRiderCoordinator`. |
| `roadflare-rider/src/main/java/com/roadflare/rider/viewmodels/FareCoordinator.kt` | Adapt or replace: owns local `RouteResult` data class (NOT the `:common` one). |
| `roadflare-rider/src/main/java/com/roadflare/rider/viewmodels/DriverQuoteCoordinator.kt` | Adapt or replace. |

**Files created (in `:common`):**

| File | Purpose |
|------|---------|
| `common/src/main/java/com/ridestr/common/coordinator/OfferCoordinator.kt` | `sendRideOffer`, `broadcastRideRequest`, `sendRoadflareOffer`, `sendRoadflareToAll`, batch cancellation, pre-confirmation driver monitoring (#22). |
| `common/src/main/java/com/ridestr/common/coordinator/PaymentCoordinator.kt` | HTLC lock/confirm, escrow-bypass gating, payment-path persistence, completion/refund flows. |
| `common/src/main/java/com/ridestr/common/coordinator/RoadflareRiderCoordinator.kt` | Kind 30011/3186/3188/3189 flows, follower/driver state sync, stale-key refresh. |

**Files NOT touched:** `rider-app/RiderModeScreen.kt`, `rider-app/HistoryScreen.kt`, all drivestr files.

---

### Issue #66 — DriverViewModel Decomposition

**Files modified:**

| File | Change |
|------|--------|
| `drivestr/src/main/java/com/drivestr/app/viewmodels/DriverViewModel.kt` | Major: extract availability/acceptance/roadflare-driver logic. `DriverUiState`, `DriverRideSession`, local `PaymentStatus` stay in this file during the PR. |
| `drivestr/src/main/java/com/drivestr/app/MainActivity.kt` | Minor: construct/inject three new coordinators. |

**Files created (in `:common`):**

| File | Purpose |
|------|---------|
| `common/src/main/java/com/ridestr/common/coordinator/AvailabilityCoordinator.kt` | Kind 30173 publishing, online/offline/ROADFLARE_ONLY modes, NIP-09 deletion on ride start, geohash. |
| `common/src/main/java/com/ridestr/common/coordinator/AcceptanceCoordinator.kt` | `acceptOffer`, `acceptBroadcastRequest`, wallet-pubkey handshake, AtomicBoolean CAS race guard, deferred HTLC locking. |
| `common/src/main/java/com/ridestr/common/coordinator/RoadflareDriverCoordinator.kt` | Follower lifecycle, key rotation, Kind 30014 location broadcast, Kind 3189 ping + rate limiting. |

**Files NOT touched:** `DriverModeScreen.kt`, `DriverStage.kt`, all rider-app and roadflare-rider files.

> **`AvailabilityCoordinator` note:** `pendingOffers` and `pendingBroadcastRequests` live in `DriverRideSession` (inside `DriverViewModel.kt`). The coordinator returns/emits these; the ViewModel writes them to UiState. The split point is: coordinator owns Nostr subscription + parsing; ViewModel owns `StateFlow` fields.

---

### Issue #67 — Screen Component Extraction

**Files modified:**

| File | Extractions |
|------|-------------|
| `rider-app/src/main/java/com/ridestr/rider/ui/screens/RiderModeScreen.kt` (3,738 lines) | → `RideRequestPanel`, `ActiveRidePanel`, `CompletionPanel`, `CancelDialogStack` |
| `drivestr/src/main/java/com/drivestr/app/ui/screens/DriverModeScreen.kt` (2,583 lines) | → `AvailabilityControls`, `OfferInbox`, `ActiveRidePanel`, `RoadflareFollowerList` |
| `roadflare-rider/src/main/java/com/roadflare/rider/ui/screens/RideTab.kt` (783 lines) | ⚠️ See constraint below |
| `roadflare-rider/src/main/java/com/roadflare/rider/ui/screens/DriverNetworkTab.kt` (707 lines) | → `FollowedDriverList`, `PendingFollowerList`, `AddDriverDialog` |
| `roadflare-rider/src/main/java/com/roadflare/rider/ui/screens/HistoryScreen.kt` (556 lines) | → `HistoryList`, `HistoryEntryCard`, `HistoryFilterBar` |
| `rider-app/src/main/java/com/ridestr/rider/ui/screens/HistoryScreen.kt` (591 lines) | → `HistoryList`, `HistoryEntryCard`, `HistoryFilterBar` |

**Files created:** Component `.kt` files under:
- `rider-app/.../ui/screens/components/`
- `drivestr/.../ui/screens/components/`
- `roadflare-rider/.../ui/screens/components/`

**Files NOT touched:** Any ViewModel, any `:common` file, any MainActivity.

---

## Part 2: File-Level Overlap Matrix

`✅` = confirmed touch | `⚠️` = conditional hazard | `—` = not touched

| File | #65 | #66 | #67 | Hazard? |
|------|:---:|:---:|:---:|:-------:|
| `rider-app/.../RiderViewModel.kt` | ✅ | — | — | none |
| `rider-app/.../MainActivity.kt` | ✅ minor | — | — | none |
| `rider-app/.../RiderModeScreen.kt` | — | — | ✅ | none |
| `rider-app/.../HistoryScreen.kt` | — | — | ✅ | none |
| `drivestr/.../DriverViewModel.kt` | — | ✅ | — | none |
| `drivestr/.../MainActivity.kt` | — | ✅ minor | — | none |
| `drivestr/.../DriverModeScreen.kt` | — | — | ✅ | none |
| `roadflare-rider/.../RiderViewModel.kt` | ✅ | — | — | none |
| `roadflare-rider/.../RideSessionManager.kt` | ✅ | — | — | none |
| `roadflare-rider/.../ChatCoordinator.kt` | ✅ | — | — | none |
| `roadflare-rider/.../FareCoordinator.kt` | ✅ | — | — | none |
| `roadflare-rider/.../DriverQuoteCoordinator.kt` | ✅ | — | — | none |
| `roadflare-rider/.../RideTab.kt` | ⚠️ indirect | — | ✅ | **SEE BELOW** |
| `roadflare-rider/.../DriverNetworkTab.kt` | — | — | ✅ | none |
| `roadflare-rider/.../HistoryScreen.kt` | — | — | ✅ | none |
| `common/.../coordinator/OfferCoordinator.kt` | ✅ CREATE | — | — | none |
| `common/.../coordinator/PaymentCoordinator.kt` | ✅ CREATE | — | — | none |
| `common/.../coordinator/RoadflareRiderCoordinator.kt` | ✅ CREATE | — | — | none |
| `common/.../coordinator/AvailabilityCoordinator.kt` | — | ✅ CREATE | — | none |
| `common/.../coordinator/AcceptanceCoordinator.kt` | — | ✅ CREATE | — | none |
| `common/.../coordinator/RoadflareDriverCoordinator.kt` | — | ✅ CREATE | — | none |

### The RideTab.kt Conditional Hazard

`roadflare-rider/RideTab.kt` imports four types from the roadflare-rider viewmodels package:
- `RideSession` and `DriverInfo` — defined in `RideSessionManager.kt`
- `ChatMessage` — defined in `RideSessionManager.kt`
- `RouteResult` — defined in `FareCoordinator.kt` (a roadflare-rider-local duplicate of `:common`'s `RouteResult`)

**If** #65's step 4 deletes these files or moves these types to `:common`, then `RideTab.kt`'s imports need updating — which would conflict with a concurrent #67 session also editing `RideTab.kt`.

**If** #65's step 4 treats the roadflare-rider coordinator files as adapters (keeping the same type names/packages and delegating internally to `:common`), then `RideTab.kt`'s imports are stable and both sessions can run cleanly.

**Resolution strategy** (enforced in Session A's execution plan): #65 step 4 MUST keep existing public types in their current packages for the duration of this PR. Type consolidation (merging the local `RouteResult` with `:common`'s) is a follow-up issue, not part of #65.

---

## Part 3: Pair Classifications

### #65 ↔ #66 — FULLY PARALLEL

Zero file overlap. Different app modules. New coordinator files in `:common` are all unique filenames in a new package that doesn't yet exist (`common/coordinator/` doesn't exist today — confirmed by `ls`). No shared mutable file. No build.gradle change required (Android library plugin auto-discovers `.kt` files — confirmed by inspecting `common/build.gradle.kts`).

**Verdict: open both sessions simultaneously, merge in any order.**

### #65 ↔ #67 — SEQUENCEABLE (run in parallel with one scope constraint)

`rider-app/RiderModeScreen.kt` imports `RiderUiState`, `RideStage`, `RiderViewModel` from rider-app's viewmodels package. Per #65's explicit design ("ViewModel keeps UI-state composition"), these types stay in `RiderViewModel.kt` — no package migration. `RiderModeScreen.kt` imports are stable through the entire #65 PR lifetime.

The only conditional hazard is `roadflare-rider/RideTab.kt` (covered above). Scoping #67 to defer RideTab's state-binding component extraction resolves it.

**Verdict: run in parallel. #67 session is pre-authorized to work on all screens except RideTab's state-binding components until #65 step 4 is merged.**

### #66 ↔ #67 — SEQUENCEABLE (clean parallel)

`drivestr/DriverModeScreen.kt` imports `DriverUiState`, `DriverViewModel`, and a local `PaymentStatus` from `drivestr/viewmodels/DriverViewModel.kt`. #66 keeps `DriverUiState` in that file. The component extractions in #67 accept slices of the existing `DriverUiState` as narrower props — they don't require UiState to change shape. Zero file overlap.

**Verdict: run in parallel, no constraints.**

---

## Part 4: Can #67 Start Before #65 and #66 Land?

| Screen | Start now? | Reason |
|--------|:----------:|--------|
| `rider-app/RiderModeScreen.kt` | ✅ YES | `RiderUiState` stays in rider-app — components just take narrower slices of the existing type |
| `drivestr/DriverModeScreen.kt` | ✅ YES | Same reasoning; `DriverUiState` stays in drivestr VM |
| `roadflare-rider/DriverNetworkTab.kt` | ✅ YES | Imports directly from `:common` and `FollowedDriversRepository` — no ViewModel dependency at all |
| `roadflare-rider/HistoryScreen.kt` | ✅ YES | Imports only `:common` types |
| `rider-app/HistoryScreen.kt` | ✅ YES | Same |
| `roadflare-rider/RideTab.kt` (scaffold/layout only) | ✅ YES | Can extract layout-stable composables that don't bind to `RideSession`/`DriverInfo`/`ChatMessage`/`RouteResult` |
| `roadflare-rider/RideTab.kt` (state-binding components) | ⚠️ WAIT | Wait for #65 step 4 to land, then rebase #67 branch and complete |

**The issue body's blanket "land after #65 and #66" is overly conservative for 5 of 6 targets.** The rider-app and drivestr screens' component props come from existing `UiState` fields — no coordinator API shape is needed to define those props.

---

## Part 5: Execution Plan

### Three parallel sessions

#### Session A — Issue #65 (RiderViewModel Decomposition)

- [ ] Open worktree: `git worktree add .claude/worktrees/issue-65 -b feature/issue-65-coordinator-extraction`
- [ ] Open draft PR: `gh pr create --draft --title "feat(rider): extract OfferCoordinator, PaymentCoordinator, RoadflareRiderCoordinator" --body "Closes #65"`
- [ ] **Step A-1:** Create `common/.../coordinator/OfferCoordinator.kt` — move `sendRideOffer`, `broadcastRideRequest`, `sendRoadflareOffer`, `sendRoadflareToAll`, batch-cancel logic, pre-confirmation driver monitoring out of `RiderViewModel`. Write unit tests for each public method. Commit.
- [ ] **Step A-2:** Create `common/.../coordinator/PaymentCoordinator.kt` — move HTLC lock, confirmation, escrow-bypass gating, payment-path persistence, completion/refund. Write unit tests. Commit.
- [ ] **Step A-3:** Create `common/.../coordinator/RoadflareRiderCoordinator.kt` — move Kind 30011/3186/3188/3189 flows, follower state sync, stale-key refresh. Write unit tests. Commit.
- [ ] **Step A-4:** Wire coordinators into `rider-app/RiderViewModel.kt` — ViewModel delegates to coordinators; logic is removed not copied. Run full test suite. Commit.
- [ ] **Step A-5 (LAST):** Migrate `roadflare-rider/RiderViewModel.kt` to consume the same coordinators. **Constraint: keep `RideSession`, `DriverInfo`, `ChatMessage`, and `RouteResult` in their current packages as stable public types — do not move them to `:common` in this PR.** If their implementations delegate to `:common` equivalents internally, that's fine. Commit.
- [ ] Mark PR ready for review.

**Coordinator public API review checklist (per issue instructions, SDK-extraction-ready):**
- Visibility: prefer `internal` for implementation details; every public symbol needs explicit KDoc
- Package: `com.ridestr.common.coordinator` — keep flat for now, no sub-packages
- No Android `Context` or `ViewModel` dependencies in coordinator constructors (must be unit-testable)
- All public state exposed as `StateFlow<T>` (not `MutableStateFlow`)

**Merge strategy: squash.** ~30 refactor commits into one clean commit per coordinator.

---

#### Session B — Issue #66 (DriverViewModel Decomposition)

- [ ] Open worktree: `git worktree add .claude/worktrees/issue-66 -b feature/issue-66-coordinator-extraction`
- [ ] Open draft PR: `gh pr create --draft --title "feat(drivestr): extract AvailabilityCoordinator, AcceptanceCoordinator, RoadflareDriverCoordinator" --body "Closes #66"`
- [ ] **Step B-1:** Create `common/.../coordinator/AvailabilityCoordinator.kt` — move Kind 30173 publishing, online/offline/ROADFLARE_ONLY mode transitions, NIP-09 deletion on ride start. Write unit tests. Commit.
- [ ] **Step B-2:** Create `common/.../coordinator/AcceptanceCoordinator.kt` — move `acceptOffer`, `acceptBroadcastRequest`, wallet-pubkey handshake, **move the `AtomicBoolean confirmationInFlight` and `hasAcceptedDriver` CAS guards unchanged** (do not change their semantics). Write concurrency unit tests. Commit.
- [ ] **Step B-3:** Create `common/.../coordinator/RoadflareDriverCoordinator.kt` — move follower lifecycle (PENDING→APPROVED→KEY_SENT→ACTIVE→MUTED), key rotation, Kind 30014 location broadcast, Kind 3189 ping handling + `DriverPingRateLimiter`. Write unit tests. Commit.
- [ ] **Step B-4:** Wire coordinators into `drivestr/DriverViewModel.kt`. Run full test suite. Commit.
- [ ] Mark PR ready for review.

**`PaymentStatus` note:** `DriverViewModel.kt` has a local `PaymentStatus` enum; `common/PaymentModels.kt` has a separate one. When `AcceptanceCoordinator` is created in `:common`, use `common/PaymentModels.PaymentStatus` (not the ViewModel-local one). Consolidate the ViewModel-local enum by making it a typealias or delegating — include that cleanup in Step B-4.

**Same coordinator public API review checklist as Session A.**

**Merge strategy: squash.**

---

#### Session C — Issue #67 (Screen Component Extraction)

- [ ] Open worktree: `git worktree add .claude/worktrees/issue-67 -b feature/issue-67-screen-components`
- [ ] Open draft PR: `gh pr create --draft --title "refactor: decompose high-churn Compose screens into focused components" --body "Closes #67"`

**Phase C-1 (start immediately — no dependencies):**

- [ ] **C-1a: `RiderModeScreen.kt`** — extract `RideRequestPanel`, `ActiveRidePanel`, `CompletionPanel`, `CancelDialogStack` into `rider-app/.../ui/screens/components/`. Each component takes the narrowest `RiderUiState` slice it needs (not the full object). Target ≤200 lines per component. No behavior changes. Commit.
- [ ] **C-1b: `DriverModeScreen.kt`** — extract `AvailabilityControls`, `OfferInbox`, `ActiveRidePanel`, `RoadflareFollowerList` into `drivestr/.../ui/screens/components/`. Same prop-narrowing rule. Commit.
- [ ] **C-1c: `rider-app/HistoryScreen.kt`** — extract `HistoryList`, `HistoryEntryCard`, `HistoryFilterBar`. Imports from `:common` only — no ViewModel dependency to worry about. Commit.
- [ ] **C-1d: `roadflare-rider/HistoryScreen.kt`** — same extraction. Commit.
- [ ] **C-1e: `DriverNetworkTab.kt`** — extract `FollowedDriverList`, `PendingFollowerList`, `AddDriverDialog`. No ViewModel dependency — screen imports directly from `:common`. Commit.

**Phase C-2 (depends on #65 step A-5 being merged):**

- [ ] **C-2a: Rebase `feature/issue-67` on `feature/issue-65` (or main after #65 merges).** Verify `RideTab.kt` imports (`RideSession`, `DriverInfo`, `ChatMessage`, `RouteResult`) are still in their original packages. If so, proceed. If #65 moved them, fix imports first.
- [ ] **C-2b: `RideTab.kt`** — extract three-panel split (`RideRequestPanel`, `ActiveRidePanel`, `CompletionPanel`) plus `DriverPicker`. Commit.
- [ ] Mark PR ready for review.

**Merge strategy: rebase.** Pure UI refactor with no semantic changes — rebase keeps history linear and easier to bisect if a visual regression appears.

---

## Part 6: Merge Order

```
Session A ──────────────────────────────────────► merge A (any time after green)
Session B ──────────────────────────────────────► merge B (any time after green; independent of A)
Session C Phase 1 ──────────────────────────────► merge C-phase-1 (any time after green)
                                     (wait for A step 5)
Session C Phase 2 ──────────────────────────────► merge C-phase-2 (after A is merged)
```

All three PRs can be reviewed and merged in parallel. C-phase-2 (RideTab) is the only sequenced step.

---

## Part 7: Flags and Contradictions

### Flag 1 — Issue #67 ordering is too conservative
The issue body says "Land after #65 and #66." This is only required for `roadflare-rider/RideTab.kt`'s state-binding components. Five of the six screen targets have zero dependency on coordinator API shapes. The #67 session should start immediately and not wait.

### Flag 2 — PaymentStatus type duplication
`DriverViewModel.kt` owns a local `PaymentStatus` enum. `common/PaymentModels.kt` has a parallel one. When `AcceptanceCoordinator` lands in `:common`, use the `:common` enum and clean up the duplication in the same PR. If left unresolved, `DriverModeScreen.kt` (which imports `com.drivestr.app.viewmodels.PaymentStatus`) will need updating in a follow-up. Flag this for the #66 executor.

### Flag 3 — roadflare-rider already partially decomposed
`roadflare-rider/viewmodels/` has `ChatCoordinator.kt`, `FareCoordinator.kt`, `DriverQuoteCoordinator.kt`, `RideSessionManager.kt`. The #65 session must decide in step A-5 whether these become adapters for `:common` classes or get deleted. **Recommendation: adapter pattern first** — replace their internals with `:common` coordinator calls while keeping public type names stable. Deletion is a follow-up. This minimises the blast radius on `RideTab.kt` and keeps #65 from expanding into a type migration.

### Flag 4 — Hilt (#52) is in flight
Both #65 and #66 coordinators will need `@Singleton` injection when #52 lands. **Do not add Hilt annotations in these PRs.** Coordinators should be constructable with plain constructor injection. Add a `// TODO(#52): convert to @Singleton @Inject` comment on each coordinator class. This prevents merge conflicts with the in-flight #52 branch.

### Flag 5 — `RouteResult` duplication
`roadflare-rider/viewmodels/FareCoordinator.kt` defines a local `data class RouteResult(...)`. The `:common` routing package also has `RouteResult`. These are currently separate types. Do NOT consolidate in #65 — it would expand scope significantly. Open a new issue tracking `roadflare-rider RouteResult` → `:common` migration after #65 merges.

### Flag 6 — HistoryScreen scope
Issue #67 churn table lists only roadflare-rider's `HistoryScreen.kt` (3 commits, 209 avg lines). `rider-app/HistoryScreen.kt` is not in the churn table but exists at 591 lines with identical structure. The issue says "five top-level Compose screens" — the fifth in the list is the roadflare-rider one. Whether `rider-app/HistoryScreen.kt` is in scope is ambiguous. **Recommendation:** include it in #67 since the extraction pattern is identical and the PR is already touching it in the sister app — more consistent than leaving it undecomposed.

---

## Summary

| Pair | Classification | Decision |
|------|:-------------:|:--------:|
| #65 ↔ #66 | Fully parallel | 2 simultaneous sessions |
| #65 ↔ #67 | Sequenceable (one constraint) | 2 simultaneous sessions; #67 defers RideTab state components |
| #66 ↔ #67 | Sequenceable (clean) | 2 simultaneous sessions |

**Recommended: 3 parallel worktree sessions open at once.** All three draft PRs open immediately. Sessions A and B run to completion independently. Session C runs Phase 1 immediately and Phase 2 after A merges. User reviews and merges each PR manually. Squash for A and B; rebase for C.
