# Agent Operating Model — Decompose · Verify · Review · Reintegrate (DVRR)

## TL;DR

The **default way of working** for every non-trivial task is autonomous and
parallel-first. **The main agent's primary job is to orchestrate subagents, not
to execute** — it should run almost all work (implementation *and*
investigation) inside subagents, because that is where the right **model,
temperature, and reasoning effort** are chosen for each task (the primary lever
for inference cost and determinism) and where the orchestrator's context is
preserved. Concretely: **decompose** the work into the smallest independent
units, **delegate** them to subagents and run them in parallel, **verify** each
unit as fully as can be done safely, subject each to **adversarial review until
no major findings remain (capped at 8 iterations — then record residual risk and
escalate)**, **re-verify** after any review-driven change, then **commit
each unit separately and reintegrate it onto `master`**. The gate chain — not a
human prompt — is the authority to ship. Scale the ceremony to the task: full
DVRR for substantial/risky work, a lightweight path for small changes, a direct
edit for trivial ones.

This rule is the *spine* that ties together the existing rules — it does not
replace them. Each phase below points at the rule that owns the detail.

## The Flow

```mermaid
flowchart LR
    A["Decompose
    smallest independent units"] --> B["Delegate
    subagents, in parallel"]
    B --> C["Verify
    tests, build, lint, dry-run"]
    C --> D["Adversarial review
    until no major findings
    (≤8 iterations)"]
    D --> E{Findings?}
    E -->|"major (<8 iters)"| F["Fix"] --> C
    E -->|"major (8th iter)"| X["Record residual risk
    escalate — do not commit"]
    E -->|none| G["Commit unit
    separately"]
    G --> H["Reintegrate
    rebase onto master, push"]
    H --> I["Summarise
    done / remaining / blockers"]
```

## The Phases

| Phase | What it means | Owned by |
|-------|---------------|----------|
| **Decompose** | Break work into the smallest practical units that can be implemented, verified, reviewed, and committed independently. Maximises parallelism and isolates blast radius. | `taskify-agent` / `/taskify` |
| **Delegate** | The orchestrator's primary job is to delegate, not to do. Hand the **overwhelming majority of execution — implementation *and* investigation** — to subagents and run independent units concurrently. Delegating is also how routing happens: a subagent is where the right **model, temperature, and reasoning effort** are selected for the task, which is the main lever for inference cost and determinism. For exploratory units (ideation, spec/design, ambiguous problems), stage that routing as a descending-temperature explore→refine→validate pipeline ([[multi-pass-temperature]]). Do work inline only for the trivial residue where delegation adds no value. Default to delegation unless the work is tightly coupled, must be sequenced, or is too ambiguous to split safely. | [[subagent-routing]], [[multi-pass-temperature]], AGENTS.md routing table |
| **Isolate** | **No work runs in the bare checkout** — every independent *session* (the primary interactive session, parallel windows, long autonomous runs) works in its **own worktree** on a local-only branch, **even when it makes no changes** (read-only investigation/analysis/review included). Helper subagents spawned by a primary **share its tree** so they can review its uncommitted in-flight work; isolating them would break the review gate. Isolation is **between independent sessions, not within one**. | [[worktree-workflow]] |
| **Verify** | Verify each unit as fully as is *safe*: unit/integration/e2e tests, build, lint, static analysis, type checks, Docker builds, and non-destructive runtime checks (`--dry-run`, `terraform plan`, `--version`, validation flags, executing scripts you wrote). **If it can be safely verified, verify it**; otherwise use the strongest safe substitute. | [[testing-policy]], [[commit-workflow]] (Step 2) |
| **Review** | Subject each unit to adversarial review on a fresh context / different model, applying the 8-lens constitution. The reviewer tries to *disprove* the change, not bless it. Repeat until no major (CRITICAL/MAJOR) findings remain **or 8 review iterations are reached** — at the cap, record residual risk and escalate rather than reintegrate as if converged (see [[review-constitution]] Iteration Protocol). | [[review-constitution]]; commit gate uses `review-cheap` (per [[commit-workflow]] Step 4), merge-to-master escalates to `review-final`; `code-reviewer` is the quick pre-commit check only |
| **Re-verify** | Any review-driven change re-triggers the relevant verification — fixes regress. No unit is complete until post-review verification passes. | [[commit-workflow]] (Step 4 — re-run on BLOCK) |
| **Commit** | One coherent unit → one commit. Never bundle unrelated changes. Preserves traceability, reviewability, and clean rollback. | [[commit-workflow]] |
| **Reintegrate** | Rebase the unit onto the latest `master` and fast-forward push — **linear history, no merge commit** (never `git merge`/`--no-ff`/non-`--rebase` pull/integration branch). Conflicts surface here; resolve them, then re-verify. Concurrent rebases serialise through the `flock` rebase lock. | [[worktree-workflow]] (steps 7–8), `/worktree-merge` |

## Parallelism Limits (Hard Caps)

Parallelism is the default, but it is **bounded**. These caps are absolute and
**must not** be exceeded:

- **No more than 10 active subagents at any one time.**
- **No more than 10-way parallelism at any one time.**

Queue or defer work rather than exceed a cap, and say so when work is deferred
for this reason. Apply a **lower** effective limit when warranted by task
complexity, cost budget, model availability, repository-contention risk,
verification capacity, or operational constraints; the effective limit and its
rationale **must be recorded**. The caps bound coordination cost and merge
risk; they intentionally forgo unbounded throughput. (Spec:
`docs/operations/ai-sdlc-integration-spec.md` §8.1–§8.2.)

Achieved parallelism against these caps, and the **cause** of any serialisation
below them, are measured and recorded per §18.7 (see [[metrics]] for the
utilisation and serialisation-cause definitions). Only cap-bound time argues for
the caps being the bottleneck; the rest argues for better **Decompose** (above)
or contention reduction ([[worktree-workflow]]).

## Budget & Liveness (OP5 / OP11)

Every workflow runs against a **bounded budget** — time, steps, and inference
cost. Two conventions bound it:

- **Budget (OP5 / §20):** when a unit is about to exceed its budget, the
  orchestrator **MUST defer or escalate rather than silently exceed it.** Routing
  each task to the right model/temperature is the primary cost lever (spec §9);
  the budget is the backstop.
- **Liveness (OP11):** on non-progress or looping — repeating the same failed
  step, oscillating reviews, no forward movement — the orchestrator **MUST
  stop and escalate** rather than consume budget indefinitely.

Hard enforcement is **framework-limited today**: opencode does not yet expose a
token/step-accounting API the orchestrator could trip on automatically. Until it
does, the *control* is this convention plus recording budget/liveness outcomes in
the decision-log telemetry block ([[decision-log]], [[metrics]] §22.6 open
thresholds) — not an automatic cut-off.

## Autonomy & The Commit Gate

**The gate chain is the authority to ship — not a human prompt.** Once a unit
passes the full chain (classify → validate → changelog → adversarial review with
a PASS verdict → re-verify), the agent **commits and pushes to `master`
autonomously, without waiting to be asked.** The adversarial review is the one
defined in [[commit-workflow]] Step 4 — that rule is the single source of truth
for which reviewer runs; the `/worktree-merge` path escalates to the
authoritative `review-final` at its Gate 3 before a worktree branch lands on
`master`. This is the repository's standing authorization: the strong, mandatory
gates *replace* human pre-approval. It applies to interactive and autonomous
sessions alike.

This autonomy is bounded by hard rules that **remain fully in force**:

- **Control changes are not autonomous.** Changes to the controls AI is judged by
  (rules, agent prompts, model/temperature routing, the review constitution,
  CI/test gates) are the **higher-scrutiny class** — gated-approval with the
  authoritative `review-final` and the evaluation-harness gate, never
  auto-committed. See [[risk-authority-classification]], [[control-integrity]],
  and [[commit-workflow]] (Step 1).
- **Gates are mandatory and fail-closed.** If any gate cannot run or does not
  return a clean PASS (tests fail, review returns BLOCK, the review subagent is
  unavailable, lint errors), **do not commit**. Stop, surface the failure, and
  leave the work for inspection. Autonomy means shipping *verified* work, never
  *unverified* work.
- **Destructive git commands still require explicit confirmation** — see
  [[git-safety]]. Auto-commit and auto-push of *new* commits is authorized;
  `reset --hard`, `push --force`, history rewrites of pushed commits, `clean
  -fd`, and discarding uncommitted work are **not**.
- **Commit hygiene holds** — stage explicit paths only (never `git add .`),
  commit only files this session changed, re-read before editing, hold the
  commit lock, `git pull --rebase` before push. See [[commit-workflow]].
- **The user can always interject.** Autonomous is not uninterruptible — a
  user instruction at any point overrides the default.

## Scale The Ceremony To The Task

DVRR is a **strong default, not a rigid form**. Apply judgement (this mirrors
the simplicity ethos in [[coding-principles]] and the
[[documentation-style]] rule):

| Task shape | DVRR path |
|------------|-----------|
| **Substantial / multi-file / risky / cross-module** | Full DVRR — decompose, delegate to parallel subagents, full verify, multi-round adversarial review, separate commits, gated reintegration. (The session is already in its own worktree by default — see Isolate above.) |
| **Small, single-file, low-risk change** | Lightweight — implement inline, run the [[commit-workflow]] gate chain (a single review pass, targeted verification), single commit. No decomposition; no *extra* worktree beyond the session's own. |
| **Trivial — typo, comment, doc one-liner** | Direct edit + the minimal relevant check (link/glob check, `bash -n`, etc.), then the [[commit-workflow]] gate chain (skip-conditions may apply). Skip decomposition and multi-round review. The session is **still in its own worktree** (it's cheap — shared `.git` object store); what scales down is the *merge ceremony*, not the isolation. |

Never manufacture ceremony that adds no safety: spinning up parallel
subagent fan-outs and multi-round adversarial review for a one-line fix is the
over-engineering that [[coding-principles]] warns against. The worktree itself
is **not** ceremony to skip — it is near-free and the isolation invariant holds
for every session (see [[worktree-workflow]]); only the *merge gate chain* scales
with risk.

## Clarify Well, Rarely

Work from the strongest safe assumptions and proceed; escalate only when
ambiguity **materially affects correctness, safety, or intent** (see
[[coding-principles]]). When you must ask, make answering cheap — prefer a
structured question (the `AskUserQuestion` tool) that states:

- **What is unclear**, and **why it matters**
- A **recommended option** first, then the alternatives
- The **likely impact** of each choice

Never force the user to reconstruct context from scratch.

## Summarise After Each Batch

When a batch of parallel units completes, give a concise summary so a human can
grasp status at a glance:

- **Done** — units finished, key outcomes, verification performed, major review
  findings resolved, commits produced / reintegrated.
- **Remaining** — outstanding units, blocked items, unresolved decisions, next
  recommended steps, and whether further parallelisation is possible.

Lead with the bottom line, per [[documentation-style]].

## Relationship To Heavy Orchestration

For large fan-outs (audits, migrations, broad multi-unit work) the `Workflow`
tool encodes this same decompose → parallel-verify → adversarially-review →
reintegrate shape deterministically — but it requires **explicit user opt-in**
(see its tooling rules). DVRR via direct subagents is the always-on default;
`Workflow` is the heavier, opt-in expression of the same model.
