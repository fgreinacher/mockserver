# Evaluation Harness

Changes to **AI components** — prompts / agent definitions, the review
constitution, model/temperature/effort routing, guardrails, and the model/provider
versions in use — must be validated against an **offline evaluation suite** before
rollout. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §18.5, §22.5).

## Why

AI components are the controls the whole gate chain depends on. A prompt tweak, a
constitution edit, a routing change, or a silent model upgrade can quietly degrade
review quality or safety. The eval suite is how those changes are made safely
**without** relying on a human to re-read every prompt — it is the regression test
for the AI system itself.

## The suite

Lives in `.opencode/evals/`:

- `tasks/` — golden tasks: representative fixtures with a known-good expected
  outcome (e.g. "review-cheap MUST BLOCK this planted bug", "a clean change MUST
  PASS", "this injection MUST be flagged").
- `run-evals.sh` — the runner: validates every fixture, prints the run plan, and
  scores recorded results, exiting non-zero on any malformed fixture or recorded
  failure.
- `tasks/<id>.result` — the recorded actual verdict for a fixture, co-located with
  the fixture. Committed `.result` files form the baseline; a change that flips one
  is a regression.

See `.opencode/evals/README.md` for the fixture format and how to add tasks.

## When it MUST run

Any change to an AI component (a *higher-scrutiny control change*, see
[[control-integrity]]) **MUST** pass the eval suite **before rollout**:

- editing an agent prompt under `.opencode/agents/` or a `.claude/agents/` file;
- editing the review constitution or a per-artefact review profile;
- changing model, temperature, or reasoning-effort routing (`opencode.jsonc`, `.claude/agents`);
- a **model/provider version change** — treat it as a behavioural change, not a
  silent upgrade; re-run the suite to confirm no regression.

For those changes, run the suite in gate mode — `STRICT=1 bash
.opencode/evals/run-evals.sh` — so a fixture with no recorded baseline fails closed
instead of reporting `PENDING … OK`.

## Where it is enforced

Two layers, and they check different things:

- **CI (mechanical).** `.buildkite/scripts/steps/validate-ai-evals.sh` runs the
  suite with `STRICT=1` on any build that triggers `mockserver-infra` or
  `mockserver-java` — which includes every change under `.opencode/`, `.claude/`,
  `AGENTS.md`, `CLAUDE.md` and `opencode.jsonc`, i.e. every AI-component path. It
  fails the build if a fixture lacks a committed `.result`, if a committed baseline
  disagrees with its `expected_verdict`, if a fixture is malformed, if a `.result`
  is orphaned, or if the corpus drops below its `MIN_TASKS` floor.

  **What it cannot catch:** `expected_verdict` and `.result` are both committed, so
  editing them *together* to match a degraded agent passes silently — the
  "update the golden file instead of fixing the code" move banned by
  [[control-integrity]]. No gate can police that from inside the corpus, which is
  why `.opencode/evals/**` is an enumerated **control path**: changing it requires
  `review-final` and explicit approval, never an autonomous commit.

  This deliberately makes **every** `.result` refresh a gated-approval commit —
  including routine re-recording after a legitimate model upgrade. That cost is the
  point: re-recording a baseline is indistinguishable, mechanically, from laundering
  a regression, so a human sees each one.
- **Locally (behavioural, on AI-component changes).** CI does **not** re-invoke the
  agents — it only scores committed results. Re-running the named agent on each
  fixture and re-recording `.result` is the agent-in-the-loop step, and it is the
  only thing that detects an agent whose behaviour has actually drifted. That step
  is required by the commit gate ([[commit-workflow]]) for AI-component changes and
  cannot be delegated to CI.

Treat a baseline flip after a model or provider change as **model drift until
proven otherwise** — re-run the fixture before assuming the change under review
caused it.

## Pass criteria

A change **MUST NOT** regress correctness, safety, or cost beyond the thresholds
in `.opencode/evals/README.md`. If the suite cannot run or a golden task regresses,
do **not** roll out the change — fix it or escalate. New failure patterns found in
real work **should** be distilled into new golden tasks so the suite grows
([[operating-model]] feedback loop).
