---
name: gigaplan
description: >
  Deep, attack-tested planning for non-trivial tasks. Use when a task involves
  3+ files, architectural decisions, state machine changes, cross-component
  wiring, or any change where getting it wrong is expensive. Produces a
  verified implementation plan with checkpoints and rollback criteria.
  Replaces shallow single-pass planning with a convergence loop.
---

# Gigaplan: Attack-Tested Implementation Planning

You are now in GIGAPLAN mode. Follow these instructions EXACTLY. Do not skip steps. Do not paraphrase steps into softer versions. Each phase has explicit outputs — produce them before moving to the next phase.

## Overview

You will execute the DEAAR loop: **Decompose → Explore → Architect → Attack → Refine → Emit**. The loop converges when all 5 attack questions pass with cited evidence. Maximum 3 iterations before escalating to the user.

**Anti-confabulation rules (apply to ALL phases):**
- If you cannot find a file or function: write `MISSING` — do NOT guess a path
- If a line number is approximate: write `~N` — do NOT state it as exact
- If you are uncertain about behavior: write `UNCERTAIN` — do NOT assume
- Every factual claim about the codebase MUST include a `file:line` citation from a tool result

---

## Phase 1: DECOMPOSE

**Goal:** Break the user's request into atoms — the smallest independently verifiable changes.

**Instructions:**

1. Restate the user's request in one sentence. If ambiguous, use AskUserQuestion BEFORE proceeding.

2. List every atom. An atom is a single logical change that:
   - Touches at most 3 files
   - Can be verified with a specific command or assertion
   - Has clear inputs and outputs

3. For each atom, write:
   ```
   Atom N: [name]
   - Target area: [which module/component]
   - Expected files: [best guess — will be confirmed in EXPLORE]
   - Depends on: [atom #, or "none"]
   - Verify idea: [rough verification approach]
   ```

4. Order atoms by dependency. Independent atoms can be grouped for parallel implementation.

5. **Size check:** If you have more than 8 atoms, the task may be too large. Consider splitting into subtasks and asking the user which to tackle first.

**Output:** Print the atom list. Then proceed to Phase 2.

---

## Phase 2: EXPLORE

**Goal:** Ground every atom in the actual codebase. Replace guesses with citations.

**Instructions:**

For EACH atom, do ALL of the following:

1. **Find the target files.** Use Glob to locate files by name pattern. Use Grep to find functions/classes by name. Do NOT guess file paths.

2. **Read the relevant code.** Use Read on each target file. Record:
   - Exact file path
   - Line numbers of the code you'll modify
   - Function signatures
   - Existing patterns you can reuse

3. **Find callers/dependents.** Use Grep to search for references to any function you plan to modify. Record:
   - File paths and line numbers of all callers
   - Whether callers will need updates

4. **Find existing reusable code.** Use Grep to search for similar functionality that already exists. Search for:
   - Function names with similar semantics
   - Utility functions in common/ modules
   - Existing patterns that handle similar data flows

5. **Record findings.** For each atom, update your notes:
   ```
   Atom N: [name]
   - Files confirmed: [exact paths from tool results]
   - Lines to modify: [exact line numbers from Read]
   - Callers found: [N callers at file:line, file:line, ...]
   - Reusable code: [function at file:line, or "none found"]
   - Surprises: [anything unexpected discovered]
   ```

**Parallel agents:** If atoms touch 3+ distinct areas of the codebase, launch up to 3 Explore agents (subagent_type="Explore") in a single message. Give each agent:
- A specific area to investigate
- The atom(s) it's responsible for
- Instructions to return: files read, patterns found, reusable functions with file:line

**Completion gate:** Every atom must have at least one confirmed file:line from a Read tool result. If any atom has only guessed paths, you have not finished EXPLORE.

**Output:** Print updated atom notes with all citations. Then proceed to Phase 3.

---

## Phase 3: ARCHITECT

**Goal:** Draft the implementation plan in the fixed template format.

**Instructions:**

1. **Write the plan** to `.claude/tasks/todo.md` using the TodoWrite tool or by writing directly. Use this EXACT structure:

```markdown
# [Task Title]

## Why
[1-2 sentences: what problem this solves]

## Invariants (DO NOT BREAK)
- [ ] [thing that MUST remain true, with `file:line` reference]
  (example: "Kind 30180 event structure unchanged — `RideshareEventKinds.kt:45`")
- [ ] ...

## Atoms

### 1. [Atom name]
- **Files:** `path/to/file.kt:123-145`
- **Change:** [what specifically changes — code-level description, not business-level]
- **Reuses:** [existing function at `file:line`, or "NEW — no existing equivalent"]
- **Depends on:** [atom # or "none"]
- **Verify:** `[specific command: test, grep, build]`
- [ ] **CHECKPOINT:** [concrete assertion before next atom starts]

### 2. [Atom name]
...

## Attack Log (Iteration 1/3)
[Leave empty — filled in Phase 4]

## Rollback Plan
[Leave empty — filled in Phase 6]
```

2. **Invariants rules:**
   - List every public API, event kind, data format, or contract that your change must NOT break
   - Each invariant must reference a specific file:line
   - If you can't think of any invariants, you haven't explored enough — go back to Phase 2

3. **Checkpoint rules:**
   - Each checkpoint must be verifiable with a tool (Read, Grep, Bash for tests/build)
   - "Code looks correct" is NOT a valid checkpoint
   - Valid examples: "Grep for `newFunction` in `ViewModel.kt` returns 1 result", "`:common:testDebugUnitTest` passes", "`file.kt` contains import for X"

**Output:** The plan file is written. Proceed to Phase 4.

---

## Phase 4: ATTACK

**Goal:** Red-team your own plan by answering 5 mandatory questions with evidence.

**Instructions:**

Answer EACH question below. For each answer, you MUST cite file:line evidence from your Phase 2 exploration. An answer without a citation is an automatic FAIL.

### Question 1: REUSE
> "Does equivalent code already exist in this codebase that I'm about to reinvent?"

- Search for: function names with similar semantics, utility methods, existing patterns
- Use Grep with at least 2 different search terms per atom
- **PASS criteria:** You either (a) cite the reusable code you'll use, or (b) cite the Grep results showing 0 matches for 2+ search terms
- **FAIL criteria:** You haven't searched, or you searched with only 1 term

### Question 2: BLAST RADIUS
> "What callers/dependents break if my change is wrong?"

- For each function/class you modify: Grep for all references across the codebase
- Count callers. List the ones that might be affected.
- **PASS criteria:** You've enumerated all callers with file:line and explained why each is safe or needs updating
- **FAIL criteria:** You haven't grepped for callers, or you found callers but didn't assess impact

### Question 3: STATE
> "What state transitions, side effects, or data mutations does this touch?"

- Read the state machine files if ride state is involved (`RideState.kt`, `RideStateMachine.kt`)
- Trace any `.copy()`, `.update {}`, `StateFlow.value =` mutations
- Check for event publishing side effects (Kind XXXX events)
- **PASS criteria:** Every state mutation is listed with file:line, and you've confirmed no unintended transitions
- **FAIL criteria:** You haven't traced state mutations, or you found mutations you can't explain

### Question 4: ROLLBACK
> "If implementation fails at atom N, what is the state of the system?"

- For each atom: Is it independently safe? Can you git-revert just that atom?
- Are there atoms that must succeed together (transaction-like)?
- **PASS criteria:** You've identified the rollback strategy (git revert, undo steps) and confirmed no partial corruption scenarios
- **FAIL criteria:** There's a sequence of atoms where failure mid-way leaves corrupted state with no recovery

### Question 5: VERIFY
> "How does the implementer PROVE this works?"

- Must be a specific, runnable command or tool invocation
- Must cover the primary success path and at least one failure/edge case
- **PASS criteria:** You've listed exact commands (e.g., `./gradlew :common:testDebugUnitTest --tests "ClassName.testMethod"`) that exercise the change
- **FAIL criteria:** Verification is "manual testing" or "looks correct" without a runnable check

### Recording Results

Update the Attack Log in the plan file:

```markdown
## Attack Log (Iteration N/3)
| # | Question | Evidence | Pass |
|---|----------|----------|------|
| 1 | Reuse | [Grep results: searched "X" and "Y" — 0 matches in `common/`] | Y |
| 2 | Blast radius | [`FunctionName` called at `FileA.kt:55`, `FileB.kt:102` — both safe because...] | Y |
| 3 | State | [Mutations: `_uiState.update` at `ViewModel.kt:234` — only modifies `fieldX`, no cascading] | Y |
| 4 | Rollback | [Atoms 1-3 independent, atom 4 depends on 3 but is additive-only] | Y |
| 5 | Verify | [`./gradlew :module:test` exercises new code; `Grep "newFn"` confirms wiring] | Y |
```

**Output:** The attack log with Y/N for each question. Then proceed to Phase 5.

---

## Phase 5: REFINE (conditional)

**Goal:** Fix any FAIL answers from Phase 4.

**Instructions:**

1. Count the FAILs in the Attack Log.

2. **If 0 FAILs:** Skip to Phase 6.

3. **If 1+ FAILs and iteration < 3:**
   - For each FAIL: identify what's missing (more exploration? different approach? missing atom?)
   - Go back to the specific phase that can fix it:
     - Missing citations → re-EXPLORE (Phase 2) for that atom
     - Architectural flaw → re-ARCHITECT (Phase 3) the affected atoms
     - Untestable → add a test atom or change the verification approach
   - After fixes, return to Phase 4 (ATTACK) and re-answer ALL 5 questions
   - Increment the iteration counter

4. **If 1+ FAILs and iteration = 3:**
   - You have looped 3 times and still have failures
   - Use AskUserQuestion with this format:
     ```
     GIGAPLAN: I've iterated 3 times and can't resolve these questions:
     - Question N: [specific thing I can't determine]
     - Question N: [specific thing I can't determine]
     
     I need your input on: [specific question]
     ```
   - Do NOT continue until the user responds
   - After user response: one more ATTACK pass, then EMIT regardless

**Output:** Either "All 5 PASS — proceeding to EMIT" or the AskUserQuestion.

---

## Phase 6: EMIT

**Goal:** Finalize the plan with rollback strategy and present for approval.

**Instructions:**

1. **Fill in the Rollback Plan** section:
   ```markdown
   ## Rollback Plan
   - Atoms 1-N are independently revertible via `git revert`
   - If build breaks after atom N: [specific recovery steps]
   - If tests fail after atom N: [specific recovery steps]
   - Nuclear option: `git stash` all changes and start fresh
   ```

2. **Add Implementation Notes** if needed:
   ```markdown
   ## Implementation Notes
   - [Anything the implementer needs: ordering constraints, gotchas, env setup]
   - [References to docs or prior art that informed the plan]
   ```

3. **Final completeness check** (verify all sections exist):
   - [ ] Why section (non-empty)
   - [ ] Invariants section (at least 1 invariant with file:line)
   - [ ] All atoms have: Files, Change, Reuses, Depends on, Verify, CHECKPOINT
   - [ ] Attack Log with all 5 questions answered (all Y or user-approved)
   - [ ] Rollback Plan (non-empty)

4. **Present the plan to the user.** Print a summary:
   ```
   GIGAPLAN COMPLETE
   - Task: [title]
   - Atoms: [N]
   - Attack iterations: [N/3]
   - All 5 questions: PASS
   - Plan written to: .claude/tasks/todo.md
   
   Ready to implement. Shall I proceed?
   ```

---

## Implementation Mode (Post-Approval)

When the user approves the plan and you begin implementation:

1. **Work atom by atom.** Do not skip ahead.

2. **At each CHECKPOINT:** Run the verification command/assertion. If it fails:
   - Try to fix within the current atom (1 attempt)
   - If the fix changes the approach: STOP and tell the user "Checkpoint N failed — plan may need revision"
   - Do NOT silently continue with a broken checkpoint

3. **Mark atoms complete** as you go (check the `[ ]` in the plan file).

4. **After all atoms:** Run the full verification from Attack Question 5. Report results.

5. **If any atom requires changes not in the plan:** Tell the user before making them. This is plan drift — the user decides whether to proceed or re-plan.

---

## Quick Reference

```
PHASE     │ TOOLS                    │ OUTPUT
──────────┼──────────────────────────┼─────────────────────────
DECOMPOSE │ (thinking only)          │ Atom list with targets
EXPLORE   │ Glob, Grep, Read, Agent  │ Citations for every atom
ARCHITECT │ Write/TodoWrite          │ Plan file at todo.md
ATTACK    │ (review + Grep if gaps)  │ 5-question attack log
REFINE    │ Back to needed phase     │ Updated plan + attack log
EMIT      │ Write                    │ Final plan + rollback
```

**Timing guide:**
- Simple task (3-4 atoms): ~5 min planning
- Medium task (5-6 atoms): ~10 min planning
- Complex task (7-8 atoms): ~15 min planning
- If planning exceeds 20 min: task is too large — split it
