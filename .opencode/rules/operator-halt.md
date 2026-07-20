# Operator Halt (Kill-Switch)

An operator can **immediately halt all AI SDLC activity**. Conforms to the
AI-in-SDLC spec (`docs/operations/ai-sdlc-integration-spec.md` §17 OP10).

## Engage / clear

- **Engage (global):** create the sentinel file `AGENT_HALT` at the repository's
  **main-checkout root** — e.g. `touch AGENT_HALT`, optionally writing a reason
  inside it. The halt is **global**: it applies to every session and worktree
  (the check resolves the main checkout via the shared git common dir).
- **Engage (scoped):** to pause only a subset, create a scoped sentinel
  `AGENT_HALT_<scope>` (e.g. `AGENT_HALT_commit`, `AGENT_HALT_release`). Callers
  pass the scope: `check-halt.sh commit` checks the global sentinel **and**
  `AGENT_HALT_commit`. This satisfies OP10's "halt scoped subsets" alongside the
  global stop.
- **Clear:** the **operator / user** deletes the sentinel(s). An agent **MUST
  NOT** delete, move, or otherwise bypass them without explicit user direction —
  the system must not be able to override or evade an operator halt.

`AGENT_HALT` and `AGENT_HALT_*` are gitignored, so engaging a halt never produces
a commitable change. Each detection is appended to `.tmp/halt-audit.log`
(timestamp, pid, cwd, sentinel) for an auditable record.

## What agents MUST do when halted

Before starting any new action — **especially** commits, pushes, external or
irreversible actions, or spawning subagents — check the halt:

```bash
.opencode/scripts/check-halt.sh || { echo "halted — stopping"; exit 1; }
```

If the halt is engaged, the agent **MUST**:

- **stop starting new work** (no new commits, pushes, subagents, or external actions);
- **fail safe on in-flight work** — finish or safely abandon the current step; do **not** reintegrate;
- **report** the halt to the user and **wait** for the operator to clear it.

The halt pauses **new** actions; it does not kill a tool call already executing
mid-step. It is the operator's emergency stop for incidents, runaway loops, or
any time autonomous work should pause.

## Where it is checked

**Mechanically, before every consequential tool call.** Both harnesses run the
halt check regardless of whether an agent remembers to — the enforcement does not
rely on agent compliance:

- **Claude Code:** a `PreToolUse` hook (`.opencode/scripts/check-halt-hook.sh`,
  wired in `.claude/settings.json`) runs on `Bash`/`Write`/`Edit`/`MultiEdit`/
  `NotebookEdit`/`Task`/`Agent`. If a halt is engaged it blocks the call
  (exit 2); otherwise it allows.
- **opencode:** the `operator-halt.ts` plugin's `tool.execute.before` hook throws
  on the gated tools (`bash`/`write`/`edit`/`patch`/`task`) when a halt is engaged.

Both call the same source of truth, `check-halt.sh`, and both **fail open**: if the
check itself cannot run, the tool proceeds — a per-call gate must never wedge a
session (or the concurrent worktree sessions). Read-only tools are not gated
(pausing investigation adds no safety). The hooks check the **global** sentinel
only; scoped sentinels are enforced by the gates that own them.

Because a global halt gates `Bash`, an agent cannot clear the sentinel itself —
**the operator clears it directly on disk** at the main-checkout root (see
`check-halt.sh`), not through the agent. That is intentional for a kill-switch, not
a deadlock.

**Procedurally**, the commit gate ([[commit-workflow]]) checks the halt (with the
`commit` scope) before acquiring the commit lock, and the worktree merge
([[worktree-workflow]]) before rebasing to master, so no reintegration happens
while halted. Long-running / autonomous loops should check it between iterations.
