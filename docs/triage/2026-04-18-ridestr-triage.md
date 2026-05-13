# Ridestr Triage — 2026-04-18

> Snapshot taken on `main` at `1139022` after PR #73 merged. GitNexus index at `db65a51` (4 commits stale; the gap is pure-refactor PRs that decompose existing files, so structural impact data is still meaningful — flagged where it matters).

## 1. Executive summary

Phase 1 of the god-object decomposition **landed yesterday and today** — issues #65, #66, and #67 all closed, with their state-binding tail (PR #73) merging this morning. The four merged refactors (#68/#69/#70/#73) move ~5,000 lines of UI and protocol code out of the Compose screens and the two ViewModels into focused components and `:common` coordinators, without changing behavior.

What this means for "where we stand":

- **Phase 2 (SDK extraction from `:common`) remains on hold** until phase 1 burns in production. Per the in-repo plan and the user's intent, no SDK work should start yet.
- **`RiderViewModel` and `DriverViewModel` are still the dominant hotspots** even after phase 1 — RiderViewModel has 4,218 churn lines over 31 commits in the last 100 (96 distinct callers, 181 methods). The coordinator extraction in PRs #69/#70 carved off subsystems but the ViewModels themselves remain XL classes. They are the obvious next refactor target *after* the production verification window.
- **Two issues should be closed administratively right now** as already-shipped: #52 (SettingsManager → Hilt + DataStore + SettingsRepository, shipped in `9ebb5f3`) and #58 (DriverPresenceMapper, shipped in `938b406`). Both are referenced in CLAUDE.md as "in flight" but are in fact done.
- **Two PR-#68 review tickets are the cleanest in-flight work**: #71 (DriverMode offer-card recomposition) and #72 (silent SATS→USD fallback in `formatFareAmount`). Both have well-spec'd acceptance criteria, both are explicitly deferred-from-#68, and both are S/M-effort.
- **Highest-impact bug carrying the most ambiguity** is #41 (notification audit, labeled HIGH) — broad scope, no concrete reproductions, easy to balloon. Worth a scoping pass before committing to the work.

The repo is healthy. The refactor backlog has shrunk dramatically; what's left is a mix of (a) honest bugs surfaced from the refactor reviews, (b) protocol/payment enhancements, and (c) long-tail research items.

---

## 2. Recently merged

| PR | SHA | Description | Issue |
|----|-----|-------------|-------|
| [#70](https://github.com/variablefate/ridestr/pull/70) | `3b13552` | `feat(coordinator): extract rider protocol logic into :common coordinators` — adds `OfferCoordinator` (1,695 LOC), `PaymentCoordinator` (1,305 LOC), `RoadflareRiderCoordinator` to `:common` | #65 |
| [#69](https://github.com/variablefate/ridestr/pull/69) | `f52e31a` | `refactor(drivestr): extract AvailabilityCoordinator, AcceptanceCoordinator, RoadflareDriverCoordinator` | #66 |
| [#68](https://github.com/variablefate/ridestr/pull/68) | `919ecc9` | `refactor: decompose high-churn Compose screens into focused components` — `DriverModeScreen`, `RiderModeScreen`, `HistoryScreen`, plus consolidation of common history components into `:common/ui/components/HistoryComponents.kt` | #67 (layout half) |
| [#73](https://github.com/variablefate/ridestr/pull/73) | `1139022` | `refactor(roadflare-rider): extract RideTab state-binding components` — completes the deferred portion of #67. `RideTab.kt`: 766 → 133 lines | #67 (state-binding half) |

All four were merged 2026-04-17 / 2026-04-18. Phase 1 issues (#65, #66, #67) are now closed.

---

## 3. Churn hotspots (top 15, last 100 commits, rename-aware via `git log --follow`)

Filtering: `.kt` files only, excluding tests, generated, build, and metadata. Caller counts come from GitNexus (CALLS edges into class methods) at index commit `db65a51`.

| Rank | File | Commits | Lines | Callers (distinct) | Hotspot score | Note |
|------|------|--------:|------:|-------------------:|--------------:|------|
| 1 | `rider-app/.../viewmodels/RiderViewModel.kt` | 31 | 4,218 | **96** (181 methods) | **4,218** | Still XL class even after #65/#70 extraction. Next obvious target after phase-2 hold lifts. |
| 2 | `roadflare-rider/.../ui/screens/RideTab.kt` | 8 | 1,997 | n/a | 1,997 | **Just shrunk 766→133 lines via #73**; future churn should drop dramatically. |
| 3 | `rider-app/.../ui/screens/RiderModeScreen.kt` | 13 | 1,960 | 1 (only MainActivity) | 1,960 | Touched heavily by #68 layout decomposition; expect lower future churn. |
| 4 | `common/.../coordinator/OfferCoordinator.kt` | 1 | 1,695 | new | 1,695 | **Synthetic** — single commit (PR #70 creation). Watch its future churn rate. |
| 5 | `drivestr/.../ui/screens/DriverModeScreen.kt` | 14 | 1,681 | 1 (only MainActivity) | 1,681 | Touched by #68 layout decomposition; expect lower future churn. |
| 6 | `common/.../coordinator/PaymentCoordinator.kt` | 1 | 1,305 | new | 1,305 | **Synthetic** — single commit (PR #70). Baseline, not a hotspot yet. |
| 7 | `drivestr/.../viewmodels/DriverViewModel.kt` | 20 | 1,269 | **80** (133 methods) | 1,269 | Coordinator extraction (#69) helped but core class is still large. Pair with #1 as the phase-1 leftover. |
| 8 | `drivestr/.../ui/screens/components/DriverModeScreenComponents.kt` | 1 | 981 | new | 981 | Synthetic — extracted from DriverModeScreen by #68. **#71 targets this file directly**. |
| 9 | `roadflare-rider/.../ui/screens/DriverNetworkTab.kt` | 7 | 897 | n/a | 897 | RoadFlare follower UI; touched by #68. |
| 10 | `common/.../settings/SettingsRepository.kt` | 2 | 872 | **162** | 872 | Highest fan-in in the repo. Phase-2 SDK boundary candidate. Replaced `SettingsManager` (issue #52, done). |
| 11 | `roadflare-rider/.../MainActivity.kt` | 7 | 745 | n/a | 745 | Activity wiring + Hilt entry point. |
| 12 | `roadflare-rider/.../components/RideTabComponents.kt` | 2 | 701 | new | 701 | Synthetic — receives extracted state composables from #73. |
| 13 | `common/.../ui/components/HistoryComponents.kt` | 4 | 656 | new | 656 | Consolidated by #68. **#72 targets `HistoryStatsCard` here**. |
| 14 | `common/.../nostr/NostrService.kt` | (post-rename) | n/a | **131** | n/a | Facade — second-highest fan-in after SettingsRepository. Already domain-decomposed into 4 services + CashuTokenCodec per CLAUDE.md. |
| 15 | `roadflare-rider/.../ui/screens/HistoryScreen.kt` | 4 | 620 | n/a | 620 | Touched by #68 history-component consolidation. |

**Reading the table:**

- The "synthetic" rows (#4, #6, #8, #12) are PR #70/#68/#73 *baselines*, not active churn. Their hotspot scores reflect creation diffs. Re-rank in 30 days.
- The two "real" leftover hotspots are **rows #1 (RiderViewModel) and #7 (DriverViewModel)** — both shed responsibilities to coordinators but remain large. Combined caller count is 176 — they are the public interface every consumer talks to.
- **Row #10 (SettingsRepository)** has the highest fan-in in the codebase (162 callers). This is normal for a settings store, but it's the most load-bearing seam in `:common` — if SDK extraction proceeds, this is one of the first contracts to lock down.
- **Row #14 (NostrService facade)** has 131 callers despite already being decomposed. The facade pattern is doing its job; treat it as the second key SDK seam.

---

## 4. Issue triage (26 open)

### Tier P0 — Administrative cleanup (close immediately)

| # | Title | Action | Rationale |
|---|-------|--------|-----------|
| **#52** | Migrate SettingsManager to Hilt DI + DataStore + Repository pattern | **CLOSE** as completed | Shipped in commit `9ebb5f3` ("Replace SettingsManager with DataStore + Hilt SettingsRepository (Issue #52)"). `SettingsRepository.kt` exists, three `*Application.kt` files exist with `@HiltAndroidApp`, build files have Hilt + DataStore. Only stale references to `SettingsManager` are KDoc comments in the new files. CLAUDE.md still lists this as "in flight" — out of date. |
| **#58** | Unify driver status channels via DriverPresenceMapper | **CLOSE** as completed | Shipped in commit `938b406` ("Add DriverPresenceMapper with typed gate, fix RoadFlare unavailable race (Issue #58)"). |

### Tier P1 — Do first (small, well-specified, blast radius known)

| # | Title | Category | Effort | Rationale |
|---|-------|----------|:------:|-----------|
| **#72** | bug: `formatFareAmount` SATS mode silently falls back to USD when price unavailable | bug | **S** | Concrete correctness bug surfaced by #68 third-pass review. Affects 3 call sites in `RideTabComponents.kt` headers + the same anti-pattern in `HistoryComponents.kt` (`HistoryStatsCard.totalSpentDisplay`) and `common/fiat/FiatFareFormatting.kt` (`formatFareDisplay`). Acceptance criteria are crisp. Scoped to display-formatting helpers — low blast radius. |
| **#71** | perf: Reduce recomposition cost in DriverMode offer cards | perf | **S–M** | Concrete perf hotspot in `DriverModeScreenComponents.kt` (rank #8 churn file). 1Hz `LaunchedEffect` ticker recomposes entire offer cards; unstable `Map`/`List` params break Compose child-skipping. Five named fixes in the issue. Same compositional pattern shows up in `DriverNetworkComponents.kt` (`DriverCard`) and `HistoryComponents.kt` (`HistoryStatsCard`) — fold those in. |
| **#59** | Cancel warning incorrectly mentions payment claiming for fiat/RoadFlare rides | bug | **S** | Discovered during iOS port. Driver-side text bug — mentions escrow claim for non-Cashu rides. Trivial conditional based on `paymentPath` (already process-death-stable per CLAUDE.md). |

### Tier P2 — Do next (medium effort, real value, ready to start)

| # | Title | Category | Effort | Rationale |
|---|-------|----------|:------:|-----------|
| **#41** | [HIGH] Audit notification layer across all app states and features | bug | **L** | Labeled HIGH; user-visible; broad scope (3 surfaces × ~10 states each). Risk: scope creep. **Recommend: scope this with a small spike first** — enumerate every `notify()` call site, build the matrix, then schedule per-row fixes. The bug class (phantom "Starting..." notifications, persistence after ride end) is real but needs an enumeration before a sprint estimate. |
| **#49** | Complete non-cashu defaultPaymentMethod support — UI and remaining paths | enhancement | **M** | Direct continuation of merged PR #48 (Issue #46). Three remaining items: (1) UI to change `defaultPaymentMethod`, (2) runtime guards in `sendRideOffer()` / `broadcastRideRequest()`, (3) settings-UI exposure decision. Touches PaymentCoordinator (the brand-new file) — natural time to work in it before its first deviation from creation baseline. |
| **#19** | Add backup payment methods settings (Zelle, Venmo, Cash App, etc.) | enhancement | **M** | Extends fiat-rail support, complements #49 and the already-shipped fiat path (per CLAUDE.md "Payment Method Priority (Issue #46)"). Touches `SettingsRepository` (well-shaped), `RideOfferEvent` / `DriverAvailabilityEvent` (event schemas), `ProfileBackupEvent` (Kind 30177). All extensions to existing surfaces. |
| **#6** | Feature request: more detailed geocoder search results | enhancement | **S** | Small UX win, scoped to geocoder result rendering. Touches `GeocodingService.kt` (rank-23 churn) which had a recent commit per git log. |
| **#26** | Progressive Location Disclosure: Proximity-based pickup reveal | enhancement | **M** | Privacy upgrade. **The dead code already exists** — `RiderViewModel.checkAndRevealPrecisePickup()` and `revealPrecisePickup()` are implemented but never wired. Issue body has full file-by-file plan. The expensive pieces (`RideConfirmationEvent` schema, `DriverRideStateEvent` location field) are protocol changes — coordinate with iOS port if those events are co-evolved. |

### Tier P3 — Backlog (valuable but less urgent or higher-cost)

| # | Title | Category | Effort | Rationale |
|---|-------|----------|:------:|-----------|
| **#43** | [LOW] ViewModel test harness for rider and driver flows | tracking | **L–XL** | Labeled LOW but **strategically high-leverage**. Test infrastructure already exists in `:common` (`FakeMintApi`, `FakeNip60Store`, `MainDispatcherRule`). Adding ViewModel tests would lock down the public surface of the two largest hotspots (#1, #7) — exactly the kind of safety net you want before SDK extraction. **Promote to P2 if you want to pull SDK work forward**; keep at P3 otherwise. |
| **#13** | Protocol Interoperability: Cross-App Portability & Payment Method Extensibility | tracking | **L** | Tracking issue; partially superseded — `payment_method` is in Kind 3173 (✅ done per CLAUDE.md), `payment_methods` is in Kind 30177. Remaining: `protocol_version` field, `ext_*` extension convention docs, optional Kind 30178 public profile. Rewrite or split into smaller items. |
| **#42** | [LOW] Extract hardcoded strings to string resources | enhancement | **L** | Mechanical, large surface. Blocks future localization. Best done as one focused pass per screen, not piecemeal. Unblocked anytime — no protocol or architecture coupling. |
| **#44** | Redesign withdrawal flow with calculator-style input | enhancement | **M** | Pure UX redesign, no protocol changes. Self-contained. |
| **#11** | Create proper app icons and logos | enhancement | **M** | Design-blocked, not engineering-blocked. Wait for assets, then mechanical asset swap + notification icon update in `NotificationHelper.kt`. |
| **#7** | Block lists for riders and drivers | enhancement | **M** | Uses Kind 30007 mute set with merge-with-existing read-before-write. Pattern is well-established (see `ProfileSyncAdapter` per CLAUDE.md). Protocol implications minor. |
| **#9** | Integrate tips with Cashu wallet | enhancement | **M** | Replace external-wallet stub in `TipScreen.kt` with in-app Cashu deduction. Pattern matches existing HTLC-or-P2PK ride payments. |
| **#29** | NUT-12: DLEQ Proofs for Mint Signature Verification | enhancement | **L** | Trust-minimization for mint. Touches 7 unblinding sites in `CashuBackend.kt`. Well-spec'd, good test vectors exist in NUT-12 spec. Self-contained in `CashuCrypto` + `CashuBackend`. |
| **#31** | NUT-20: Signed Mint Quotes for Output Protection | enhancement | **M** | MITM protection for mint. We already have BIP-340 Schnorr (`WalletKeyManager.signSchnorr`). Smaller than #29. |
| **#32** | Add developer option for custom routing tile source (different NPUB) | enhancement | **S** | Trivial settings + relay-subscription parameterization. Dev-only, low priority but cheap. |
| **#10** | Implement Blossom delete API for profile pictures | bug-ish | **S** | Single new API call + signed auth header. Currently leaks blobs server-side. |

### Tier P4 — Defer (research, niche, or large blocked dependencies)

| # | Title | Category | Effort | Rationale |
|---|-------|----------|:------:|-----------|
| **#30** | NUT-21/22: Mint Authentication (Clear and Blind Auth) | enhancement | **XL** | OAuth 2.0 + BAT lifecycle. Depends on #29 (DLEQ). Useful only when targeting a premium/auth'd mint — defer until that need is concrete. |
| **#25** | Implement TOR via Arti | enhancement | **XL** | APK size + bootstrap latency tradeoffs, large dependency. Niche privacy use case. Research first; full implementation is a quarter of work. |
| **#20** | Research: Bluetooth mesh protocol for Nostr event sharing | research | **L** | Pure research issue. No commitment to ship. Low urgency unless the festival-mode use case becomes concrete. |
| **#34** | GOING GLOBAL: Building out tiles and uploading to blossom | ops | **ongoing** | Operational, not engineering. Already 8 countries done. Self-tracking. |
| **#38** | Support signers (such as Amber) | enhancement | **L** | NIP-46 / Amber bunker support. Touches every signing site (event creation, NIP-44 encrypt). Architectural — defer until SDK boundary work begins (the same indirection layer would help). |

---

## 5. Recommended next work (respecting phase-2 hold)

Concrete proposals, ordered by what I'd start first:

1. **Close #52 and #58 administratively.** They're done. Update CLAUDE.md's status table to reflect reality. (5 minutes.)
2. **Land #72 (formatFareAmount silent fallback) and #71 (DriverMode recomposition) as a paired follow-up to #68/#73.** Both were explicitly deferred from #68. Both have crisp acceptance criteria. Both are S–M effort. Doing them now closes the post-#68 review loop and keeps the new component files (`HistoryComponents.kt`, `DriverModeScreenComponents.kt`, `RideTabComponents.kt`) tidy before they accrete more callers. Add #59 (cancel-warning text bug) as a third trivial fix in the same pass.
3. **Spike #41 (notification audit) into a per-call-site enumeration before estimating.** Don't commit a sprint until the matrix exists. The "audit + fix" framing is a scope-creep magnet. A 2-hour enumeration spike collapses the unknowns into a real ticket list.
4. **Defer all hotspot refactor work on RiderViewModel / DriverViewModel until the production verification window for phase 1 closes.** That's the user's stated rule. When it does close, those two classes are the obvious phase-2 prep target — combined caller count is 176, and they're the largest remaining seams in `:common`-adjacent code.

If the user wants to **pull SDK work forward despite the hold**, the highest-leverage prep is **#43 (ViewModel test harness)**. Locking down `RiderViewModel` / `DriverViewModel` behavior with tests before further extraction is the difference between SDK extraction being a clean lift and being a regression hunt.

---

## 6. Red flags

- **CLAUDE.md is partially stale.** The "Quick Implementation Status" table claims everything is ✅ COMPLETE but doesn't reflect that #52 and #58 issues are still listed as open on GitHub. Worth a sweep — at minimum, close those two issues, and consider whether the table should reference open issues for known gaps (e.g., #71 perf, #72 fare-display bug).
- **Three brand-new "synthetic hotspot" files** (`OfferCoordinator.kt`, `PaymentCoordinator.kt`, `RoadflareDriverCoordinator.kt`) were created with single 1,300–1,700 LOC commits. That's normal for an extraction PR, but they'll need their first non-trivial bug fix or feature add to reveal whether the extraction boundaries are right. Watch for:
  - Diffs that touch both the coordinator *and* the originating ViewModel in the same commit — that's a leaky boundary.
  - PRs that reach back into the ViewModel for state the coordinator should own.
  - Re-suggest a follow-up review pass once the first 3–5 such diffs land.
- **GitNexus index is 4 commits stale and the `npx gitnexus analyze` reindex fails** at the `tree-sitter-dart` git dependency (`Cannot destructure property 'package' of 'node.target' as it is null`). The four missed commits are pure refactors so impact data is still meaningful, but a reindex will be needed before the next triage. Worth opening an issue in `gitnexus` upstream or pinning to a version whose dart subdep resolves.
- **Issue #13 is partially superseded.** Several P0 items in its body (`payment_method` in Kind 3173, `payment_methods` in profile) are now done per CLAUDE.md. Consider rewriting it as a tracking issue for the *remaining* protocol-versioning work, or splitting into atomic items (`protocol_version` field; `ext_*` convention doc; Kind 30178 spec).
- **Two untracked Kotlin files in the working tree** (`rider-app/src/main/java/com/ridestr/rider/viewmodels/AvailabilityMonitorPolicy.kt` and its test) appear to be left-over local copies — PR #70 moved these to `common/src/main/java/com/ridestr/common/coordinator/`. The local copies have the same content but are no longer referenced. Safe to delete after confirming with `git diff` against the in-tree common copies.
- **No "tests not run" red flag.** `:common` test infrastructure is healthy and per CLAUDE.md "Payment Test Harness ✅ COMPLETE" + "Proof-Conservation Tests ✅ COMPLETE". The next high-value test investment is #43 (ViewModel-level harness).

---

*Generated 2026-04-18 against `main @ 1139022`. GitNexus index `db65a51` (4 commits stale, reindex blocked on upstream npm dep). 26 open issues triaged. No source files modified by this report.*
