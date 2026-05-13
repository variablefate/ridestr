# Ridestr Refresh Triage — 2026-05-01

> Refresh requested because the parent orchestrator's mental model lagged behind local code-session work. `main` at `87706d0` (12 days unchanged since PR #75 on 2026-04-19). 22 open issues. No source files modified by this report.

## Executive summary

- **Heads-up — orchestrator was missing a triage.** The dispatch prompt cited 2026-04-18 as the last triage; in fact a follow-up [`2026-04-27-ridestr-triage.md`](./2026-04-27-ridestr-triage.md) was generated 4 days ago and already covered the post-PR-#75 state, hotspot snapshot, gitnexus reindex, and a fresh 21-issue table. This refresh is the small delta on top of *that* baseline, not 04-18.
- **Code-level: zero churn.** Last merge was PR [#75](https://github.com/variablefate/ridestr/pull/75) (`87706d0`, 2026-04-19). Repo unchanged for 12 days. Phase 2 SDK extraction still on hold.
- **Release: still v0.2.6** (`853744d`, tagged 2026-02-17 — pre-refactor). The v0.2.7 manual QA plan ([`docs/qa/2026-04-18-release-manual-qa-plan.md`](../qa/2026-04-18-release-manual-qa-plan.md), 320 lines) is written but has not produced a tag — production verification of the #65/#66/#67 arc remains the gate for both the v0.2.7 cut AND lifting the phase-2 hold.
- **Issue activity since 2026-04-27: 2 closed (#19 completed, #74 won't-do), 3 opened (#76, #77, #78).** Net open count: 22.
- **Most notable new issue: #78** — confirmed cross-platform bug with iOS-side investigation already done and a partial workaround already shipped on iOS. Only drivestr can close the remaining two symptoms. Highest single new finding in this window.

## Recently merged since 2026-04-18

Only PR #75 landed *after* the 04-18 triage report was written. The other entries below merged the same day (04-17/04-18) and are listed for completeness — they are the tail end of the coordinator/screen-decomposition arc.

| PR | SHA | Closed | Description |
|---|---|---|---|
| [#75](https://github.com/variablefate/ridestr/pull/75) | `87706d0` | #59, #71, #72 | Post-refactor housekeeping + 3 P1 fixes from the #65/#66/#67 arc (formatFareAmount SATS-mode placeholder, DriverMode recomposition perf, cancel-warning copy fix). |
| [#73](https://github.com/variablefate/ridestr/pull/73) | `1139022` | (none) | refactor(roadflare-rider): extract RideTab state-binding components — final piece of the #67 arc. |
| [#70](https://github.com/variablefate/ridestr/pull/70) | `3b13552` | #65 | feat(coordinator): extract rider protocol logic into `:common/coordinator/` (OfferCoordinator, PaymentCoordinator, RoadflareRiderCoordinator, AvailabilityMonitorPolicy). |
| [#69](https://github.com/variablefate/ridestr/pull/69) | `f52e31a` | #66 | refactor(drivestr): extract AvailabilityCoordinator, AcceptanceCoordinator, RoadflareDriverCoordinator into `:common/coordinator/`. |
| [#68](https://github.com/variablefate/ridestr/pull/68) | `919ecc9` | #67 (layout) | refactor: decompose high-churn Compose screens into per-module `components/` packages. |

## Release status

- **Current tag: `v0.2.6`** (`853744d`, 2026-02-17). Released **before** the major coordinator + screen-decomposition arc — the production app users are running today does **not** include the SDK-prep refactors.
- **Target: `v0.2.7`.** QA plan ([`docs/qa/2026-04-18-release-manual-qa-plan.md`](../qa/2026-04-18-release-manual-qa-plan.md)) estimates 3h 15m–4h 45m of on-device manual bake across two physical Android devices. Plan has not run; no `v0.2.7` tag exists.
- **No regression reports from QA-in-flight.** The three issues opened since 04-27 are not v0.2.7 regressions — #76 and #77 are forward-looking cross-platform features paired with iOS, #78 is a long-standing protocol-design bug surfaced by iOS-side investigation.
- **Phase 2 hold remains correct.** Production verification of the #65/#66/#67 arc has not yet occurred, so SDK extraction from `:common` should not be pulled forward.

## Issue activity since 2026-04-27

### Closed (2)

| # | Title | Reason | Notes |
|---|---|---|---|
| [#19](https://github.com/variablefate/ridestr/issues/19) | Backup payment methods settings (Zelle/Venmo/Cash App/etc.) | COMPLETED | 5 of 6 deliverables shipped via prior PRs (drag-to-reorder UI in PR #48, ZELLE/PAYPAL/CASH_APP/VENMO enums in `RideshareEventKinds.kt:252-255`, methods in Kind 30173/3173, profile backup in Kind 30177). 6th item (per-method handles like Zelle email / Venmo username) deliberately deferred — handles exchanged via Kind 3178 chat post-confirm rather than broadcast (privacy decision). |
| [#74](https://github.com/variablefate/ridestr/issues/74) | Unify Kind 30173 `availabilityStatus` into DriverPresenceMapper | WON'T-DO | Kind 30173 already has its own typed source of truth: `drivestr/.../presence/AvailabilitySpec.kt` sealed class with 4 self-validating variants and exhaustive `when (this) -> toPublishArgs()`. Same forgot-to-update-X safety property the mapper provides for Kind 30014, just shaped around the variant data Kind 30173 needs. Folding it into `DriverPresenceMapper` would require a richer signature for marginal benefit. Decision committed in close comment. |

### Opened (3, all on 2026-04-29)

| # | Title | Companion | One-liner |
|---|---|---|---|
| [#76](https://github.com/variablefate/ridestr/issues/76) | [Feature] Live ephemeral location updates (cross-platform) | [roadflare-ios#81](https://github.com/variablefate/roadflare-ios/issues/81) | Replace periodic Kind 30012 with sub-second GPS streaming during active rides. Foreground service / Doze / battery profile concerns. Co-author shared spec doc first. **XL effort.** |
| [#77](https://github.com/variablefate/ridestr/issues/77) | [Feature] Nostr-based voice calls (Amethyst-style) | [roadflare-ios#82](https://github.com/variablefate/roadflare-ios/issues/82) | WebRTC + Nostr-signaled voice between matched driver/rider. ConnectionService/CallKit, mic permission UX, FCM wake. Multi-PR feature, not on immediate roadmap. **XL effort.** |
| [#78](https://github.com/variablefate/ridestr/issues/78) | Drivestr: Kind 3187 follow-notification triggers full key rotation | [roadflare-ios#72](https://github.com/variablefate/roadflare-ios/issues/72), workaround [#76](https://github.com/variablefate/roadflare-ios/pull/76) | **Confirmed bug.** Re-adding a previously-followed driver invalidates *every other rider's* stored RoadFlare key. Code path identified (`drivestr/.../MainActivity.kt:424-460`). iOS already shipped a partial workaround; two remaining symptoms (genuinely-new follows, backup-unreachable re-adds) are drivestr-only. **M effort.** |

## Open issue audit (22 issues, sorted by tier)

The 2026-04-27 table is mostly authoritative. Adjustments: drop closed #74/#19, add #76/#77/#78, promote #78 to **P1** as the only confirmed-bug ticket in the queue.

| Tier | # | Title | Cat | Effort | Rationale |
|------|---|-------|-----|:------:|-----------|
| **P1** | [#78](https://github.com/variablefate/ridestr/issues/78) | Drivestr: Kind 3187 triggers full key rotation; should re-deliver existing key | bug | M | Confirmed bug. iOS-side investigation complete, partial workaround already shipped — only drivestr can close the remaining two symptoms. Code path identified. **Highest priority bug in the queue.** *(New 04-29.)* |
| **P2** | [#41](https://github.com/variablefate/ridestr/issues/41) | [HIGH] Audit notification layer across all app states | bug | L | Labeled HIGH, broad scope, no concrete repro. **Spike first** — enumerate `notify()` call sites into a state-matrix before sizing. *(Unchanged from 04-27.)* |
| **P2** | [#26](https://github.com/variablefate/ridestr/issues/26) | Progressive Location Disclosure (proximity pickup reveal) | enh | M | Privacy upgrade. Dead-code wiring already exists in RiderViewModel — coordinate event-schema changes with iOS port. *(Unchanged.)* |
| **P2** | [#6](https://github.com/variablefate/ridestr/issues/6) | More detailed geocoder search results | enh | S | Small UX win, scoped to `GeocodingService.kt` rendering. *(Unchanged.)* |
| **P3** | [#43](https://github.com/variablefate/ridestr/issues/43) | ViewModel test harness for rider/driver flows | test | L–XL | Strategically high-leverage SDK prep — locks down RiderVM/DriverVM behavior before phase 2. **Promote to P2 if pulling SDK work forward.** *(Unchanged.)* |
| **P3** | [#13](https://github.com/variablefate/ridestr/issues/13) | Protocol versioning + `ext_*` extension convention | proto | M | Rescoped per prior triage. Ready to ship. **Relevance increased** — #76 and #78 are both iOS-cooperative, so the extension-field convention will pay off sooner. |
| **P3** | [#42](https://github.com/variablefate/ridestr/issues/42) | Extract hardcoded strings to `strings.xml` | enh | L | Mechanical, blocks future localization. One screen at a time. *(Unchanged.)* |
| **P3** | [#44](https://github.com/variablefate/ridestr/issues/44) | Withdrawal calculator-style flow redesign | UX | M | Self-contained UX rewrite, no protocol deps. *(Unchanged.)* |
| **P3** | [#11](https://github.com/variablefate/ridestr/issues/11) | Proper app icons + notification icons | design | M | Design-blocked. Mechanical swap once assets exist. *(Unchanged.)* |
| **P3** | [#7](https://github.com/variablefate/ridestr/issues/7) | Rider/driver block lists (Kind 30007 mute set) | enh | M | Established merge-with-existing pattern via `ProfileSyncAdapter`. *(Unchanged.)* |
| **P3** | [#9](https://github.com/variablefate/ridestr/issues/9) | Integrate tips with Cashu wallet | enh | M | Replace external-wallet stub in `TipScreen.kt` with HTLC/P2PK pattern. *(Unchanged.)* |
| **P3** | [#29](https://github.com/variablefate/ridestr/issues/29) | NUT-12 DLEQ verification | sec | L | Trust-min for mint. 7 unblinding sites in `CashuBackend.kt`. Self-contained. *(Unchanged.)* |
| **P3** | [#31](https://github.com/variablefate/ridestr/issues/31) | NUT-20 signed mint quotes | sec | M | MITM protection. Schnorr signing already in repo. Smaller than #29. *(Unchanged.)* |
| **P3** | [#32](https://github.com/variablefate/ridestr/issues/32) | Dev option: custom routing-tile NPUB | dev | S | Trivial settings parameterization. *(Unchanged.)* |
| **P3** | [#10](https://github.com/variablefate/ridestr/issues/10) | Blossom delete API for profile pictures | bug | S | Single signed-DELETE call. Currently leaks blobs server-side. *(Unchanged.)* |
| **P3** | [#76](https://github.com/variablefate/ridestr/issues/76) | [Feature] Live ephemeral location updates (cross-platform with iOS) | enh | XL | New 04-29. Companion to [roadflare-ios#81](https://github.com/variablefate/roadflare-ios/issues/81). Co-author spec doc first; gate on iOS readiness. P3 not because the feature is small — because cross-app coordination outranks unilateral execution. |
| **P4** | [#30](https://github.com/variablefate/ridestr/issues/30) | NUT-21/22 mint authentication | sec | XL | Depends on #29. Useful only when targeting auth'd mint. *(Unchanged.)* |
| **P4** | [#25](https://github.com/variablefate/ridestr/issues/25) | TOR via Arti | priv | XL | APK-size + bootstrap-latency tradeoffs, niche use case. Research first. *(Unchanged.)* |
| **P4** | [#20](https://github.com/variablefate/ridestr/issues/20) | Bluetooth-mesh research for Nostr events | research | L | Pure research, no shipping commitment. *(Unchanged.)* |
| **P4** | [#34](https://github.com/variablefate/ridestr/issues/34) | Tile coverage expansion (blossom uploads) | ops | ongoing | Ops, self-tracking. 8 countries done. *(Unchanged.)* |
| **P4** | [#38](https://github.com/variablefate/ridestr/issues/38) | NIP-46 / Amber signer support | enh | L | Architectural — touches every signing site. Defer until SDK boundary work begins. *(Unchanged.)* |
| **P4** | [#77](https://github.com/variablefate/ridestr/issues/77) | [Feature] Nostr-based voice calls (Amethyst-style) | enh | XL | New 04-29. Companion to [roadflare-ios#82](https://github.com/variablefate/roadflare-ios/issues/82). Filed for visibility; not on immediate roadmap. |

**Tier totals:** P1: 1 · P2: 3 · P3: 12 · P4: 6 · **Total: 22.**

## Churn refresh

**Skipped — no code changed.** Last commit landed 2026-04-19 (PR #75). 12 days of zero churn. The post-#75 hotspot snapshot in the [2026-04-27 triage](./2026-04-27-ridestr-triage.md#hotspot-snapshot-after-pr-75) is still 100% current. For reference:

| Class | Callers (04-27) | Note |
|---|--:|---|
| `SettingsRepository` | 167 | SDK contract priority #1 |
| `NostrService` (facade) | 155 | SDK contract priority #2 |
| `RiderViewModel` | 87 | Down from 96 pre-refactor |
| `DriverViewModel` | 78 | Down from 80 pre-refactor |
| `PaymentCoordinator` | 33 | New (PR #70) |
| `DriverPresenceMapper` | 26 | Validates #58 design |
| `OfferCoordinator` | 24 | New (PR #70) |
| `AvailabilityCoordinator` | 22 | New (PR #69) |
| `RoadflareDriverCoordinator` | 18 | New (PR #69) |
| `AcceptanceCoordinator` | 14 | New (PR #69) |
| `RoadflareRiderCoordinator` | 5 | New (PR #70) — watch whether it earns its keep |

ViewModels did **not** churn in this window — extraction is holding. Two ViewModels at 87 / 78 callers remain the largest seams; locking their behavior with #43 (test harness) before further extraction is the next-highest leverage move.

## Recommended next moves

Respect the phase-2 hold. The repo has been quiet for 12 days; the meaningful question is *what unblocks the v0.2.7 cut*. In rough order:

1. **Fix #78.** Only confirmed-bug P1, code path is identified (`drivestr/.../MainActivity.kt:424-460`), iOS already did the cross-platform investigation, and a regression test is specified in the issue body. **M effort.** Including this in the v0.2.7 target SHA strengthens the Kind 3187 handler before release.
2. **Run the v0.2.7 manual QA bake** ([`docs/qa/2026-04-18-release-manual-qa-plan.md`](../qa/2026-04-18-release-manual-qa-plan.md), 3h 15m–4h 45m). The refactor arc has been quiescent for 12 days; this is the gate for both the cut AND lifting the phase-2 hold. Sequence #78 first if that fix is small enough to land in the same QA window, otherwise QA against `87706d0` and patch #78 in v0.2.8.
3. **Spike #41** (notification-layer audit, ~2h enumeration). Independent of the QA bake. Converts the HIGH-labeled vague work into concrete tickets. *(Carried over from 04-27.)*
4. **(Optional, low-priority)** Co-author the cross-platform spec doc with the iOS team for #76 / #78 / #13. Three iOS-cooperative items are accumulating; a `docs/specs/` doc would unblock parallel implementation. Could be folded into resolving #13.

**Phase 2 prep remains #43** if a window opens — but only after v0.2.7 is in production for a real-traffic verification window.

## Red flags

- ✅ **Resolved since 04-27.** Stale `rider-app/.../viewmodels/AvailabilityMonitorPolicy.kt` and its test (flagged on 04-18 and 04-27) have been removed from disk. Confirmed via `ls`.
- ⚠️ **Stale worktree.** [`.claude/worktrees/wizardly-matsumoto-406738`](../../.claude/worktrees/wizardly-matsumoto-406738) (branch `claude/wizardly-matsumoto-406738`) sits on `919ecc9` (PR #68, fully merged). Branch has 0 commits ahead of `main` and 2 behind. Safe to remove with `git worktree remove .claude/worktrees/wizardly-matsumoto-406738 && git branch -D claude/wizardly-matsumoto-406738`. The other two stale worktrees flagged on 04-18 (`ridetab-state-binding`, `strange-kowalevski-6117ca`) are gone.
- ⚠️ **Untracked QA + triage docs.** `docs/qa/2026-04-18-release-manual-qa-plan.md`, `docs/triage/2026-04-18-ridestr-triage.md`, `docs/triage/2026-04-27-ridestr-triage.md`, and `docs/superpowers/` are all untracked in the main checkout — they exist on disk but have never been committed. After 13 days, either commit them or move to a personal scratch dir; treating them as authoritative artifacts that aren't in `git log` is a minor footgun.
- ⚠️ **Pending one-line `.gitignore` change** on main checkout (adds `.gitnexus`). Trivial, not committed.
- ⚠️ **Orchestrator mental-model drift.** The dispatch prompt cited 2026-04-18 as "the last triage" but [`2026-04-27-ridestr-triage.md`](./2026-04-27-ridestr-triage.md) is the actual most-recent baseline. The 4-day delta between 04-27 and today is small enough that this report is mostly an addendum. Future dispatches should glance at `docs/triage/` before sizing the request.
- ✅ **GitNexus** index is current (workaround documented on 04-27: prebuilt `lbugjs.node` from `@ladybugdb/core-darwin-arm64` + `npm rebuild tree-sitter-kotlin` after `--ignore-scripts` install). Index SHA matches HEAD (`87706d0`).

---

*Generated 2026-05-01 against `main @ 87706d0` (12 days unchanged, last merge PR #75 on 2026-04-19). 22 open issues. No source files modified by this report.*
