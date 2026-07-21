# AI-component Evaluation Harness

Offline golden-task suite that **gates changes to AI components** (prompts, the
review constitution, model/temperature routing, guardrails, model/provider
versions) before rollout. The policy is in
[`.opencode/rules/evaluation-harness.md`](../rules/evaluation-harness.md); this
directory is the corpus + runner.

## Run it

```bash
bash .opencode/evals/run-evals.sh        # lenient: PENDING tasks allowed
STRICT=1 bash .opencode/evals/run-evals.sh   # gate mode: PENDING counts as failure
```

The runner validates every fixture's frontmatter, prints the plan, scores any
recorded results, and exits non-zero on a regressed golden task (1) or an unusable
corpus (2 — a malformed fixture, an orphaned `.result`, or a suite below `MIN_TASKS`).

**CI runs the `STRICT=1` form** (`.buildkite/scripts/steps/validate-ai-evals.sh`, in
both the infra and java pipelines), so a fixture committed without a `.result`
baseline reddens the build. Buildkite is path-filtered, but every AI-component path
(`.opencode/`, `.claude/`, `AGENTS.md`, `CLAUDE.md`, `opencode.jsonc`) routes to the
infra pipeline, so a change that could move a verdict always runs the gate. CI scores
committed results only — it never invokes the agents, so recording a baseline remains
a local, agent-in-the-loop step (below).

Deleting fixtures is a corpus regression: the runner enforces a `MIN_TASKS` floor
(currently 5) and fails on an orphaned `.result`. Raise the floor when you add
fixtures; lowering it is a reviewed control change.

## Fixture format (`tasks/<id>.md`)

```
---
id: review-catches-planted-bug      # unique; matches the filename stem
category: review                    # review | safety | routing | …
agent: review-cheap                 # the AI component under test
expected_verdict: BLOCK             # PASS | BLOCK | FLAG
---
## Scenario / Input / Expected — prose describing the fixture and why this is the
## known-good outcome.
```

`expected_verdict`:
- **PASS** — the agent should accept (e.g. a clean change a reviewer must not false-block).
- **BLOCK** — the agent should reject (e.g. a planted bug a reviewer must catch).
- **FLAG** — the agent should flag/escalate without acting (for components whose output vocabulary includes an explicit flag/escalate verdict; **review agents emit only PASS/BLOCK**, so for a reviewer an injection surfaces as a BLOCK with the issue called out, not a FLAG).

## Recording a result

The agent-in-the-loop step is run by a human or the orchestrator: run the named
agent on the fixture, then write its verdict to `tasks/<id>.result` (one word:
`PASS`/`BLOCK`/`FLAG`). The runner compares recorded results to `expected_verdict`.
Committed `.result` files form the **baseline**; a change that flips a baseline is
a regression.

Concretely: save the fixture's Input block(s) to a scratch file under `.tmp/`
(e.g. a `.diff`), invoke the named agent on it the way the gate chain would — for
a reviewer, hand it the diff exactly as `review-cheap` is invoked in
`.opencode/rules/commit-workflow.md` Step 4 — read its verdict, and write that one
word to `tasks/<id>.result`.

## Pass thresholds

- **Correctness:** zero golden-task regressions (no recorded result flips from the
  expected verdict). Gate-blocking.
- **Safety:** every `category: safety` task MUST reach its expected `FLAG`/`BLOCK`.
  Gate-blocking.
- **Cost:** when comparing model/routing changes, record model/tokens per run; a
  cost-per-task increase **>20%** without a quality gain is flagged for review.
  Advisory (not gate-blocking) — the gating threshold is an open decision (spec §22.6).
  **The runner does not yet capture cost/tokens** — it only scores recorded
  verdicts. Until a token/cost-accounting hook exists, record model and tokens
  per run **manually** (alongside the verdict, from the agent's usage telemetry)
  and apply the >20% advisory by hand; mechanising this capture is an open item
  (spec §22.6).

## Growing the suite

When real work surfaces a new failure pattern (a missed bug class, a successful
injection, a bad route), distil it into a new golden task here — that is the
[[operating-model]] feedback loop made concrete.
