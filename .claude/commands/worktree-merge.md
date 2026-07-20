---
description: Merge the current worktree's work back to master through the 4-gate verification chain and locked rebase
---
Follow the worktree merge workflow in `.opencode/rules/worktree-workflow.md` (steps 3–8). Do not skip any gate unless the user explicitly says "skip gate X".

Run all four gates in order. **Any failure stops the merge** and leaves the worktree intact for inspection — never delete a worktree on a failed merge.

1. **Sanity check** — confirm CWD is inside a worktree (`git rev-parse --git-common-dir` differs from `.git`). If not, refuse and tell the user to `/worktree` first.

2. **Gate 1 — Tests.** Detect changed modules from `git diff --name-only origin/master...` and run targeted Maven tests **plus mockserver-core always**:
   ```bash
   ./mvnw verify -pl :mockserver-core $(...) -DforkCount=1 -DreuseForks=false -fae -Djacoco.skip=true
   ```
   For long runs, launch the build in the background (`Bash run_in_background`, or `./cmd > log 2>&1 &`) and poll the log.

3. **Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase runs checkstyle. For frontend changes also run `npm run lint && npm run build`.

4. **Gate 3 — Adversarial review.** Spawn `review-final` subagent on `git diff origin/master...HEAD`. Block on BLOCK verdict. Quote any concerns back to the user.

5. **Gate 4 — Summary & proceed.** Under the DVRR operating model (`.opencode/rules/operating-model.md`), gates 1–3 are the authority — they replace human pre-approval. Show:
   - `git diff --stat origin/master...HEAD`
   - List of changed files
   - Gate 1/2/3 results in one line each
   Then **proceed automatically** to the locked rebase. Do NOT wait for approval. Fail-closed: if any of gates 1–3 did not return a clean PASS, stop and leave the worktree intact. A user can interject at any time to halt.

6. **Locked rebase (linear history — never a merge commit).** Time the wait to acquire `flock --timeout 300 "$(git rev-parse --git-common-dir)/agent-rebase.lock"` (the common dir so all worktrees share one lock — a relative `.git/…` path resolves per-worktree and would not serialise; e.g. capture an epoch before and after the `flock`), then inside the lock:
   ```bash
   git fetch origin master --quiet
   git rebase origin/master            # rebase, never merge
   git push origin HEAD:master         # always a fast-forward after the rebase
   ```
   **Always rebase, never merge** — `master` is kept a strictly linear history with no merge commits. Do NOT `git merge` the worktree branch into master, do NOT use `git pull` without `--rebase`, and when several worktrees are ready, rebase them one at a time under the lock (never via an intermediate "integration branch"). See `.opencode/rules/worktree-workflow.md` → *Linear History — No Merge Commits*. If `flock` is missing (older macOS), fall back to the `mkdir`-based mutex documented in the rule.

   **Serialisation telemetry (§18.7 / `[[decision-log]]`).** If the lock wait was non-trivial, record `serialisation.merge_lock_s` (seconds waited on `flock`) into this unit's `.tmp/decisions/<id>.md` telemetry block. If the rebase hit conflicts, also record `serialisation.contention_s` (seconds spent resolving them).

7. **Re-verify the integrated result (spec §8.4).** If the rebase in step 6 pulled in other units' commits (i.e. `origin/master` advanced since the worktree branched), re-run the Gate 1 test command against the integrated tip before cleanup — passing in isolation does not prove the merged result passes. If the rebase was a clean fast-forward with no new upstream commits, this is a no-op.

8. **Cleanup.** On successful push only:
   ```bash
   cd "$(git rev-parse --show-toplevel)/.."
   git worktree remove "${WORKTREE_DIR}" --force
   git branch -D "${BRANCH}"
   rm -f .tmp/active-worktree
   ```

9. **Report.** Summarise to the user: gate results, commit hash on master, worktree cleaned up.

If the user provided additional instructions (e.g. "skip user approval", "skip tests because we already ran them"): $ARGUMENTS — only honour explicit skip flags, never silently drop a gate.
