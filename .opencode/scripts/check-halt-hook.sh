#!/usr/bin/env bash
# Claude Code PreToolUse wrapper for the operator halt (kill-switch).
#
# Claude's PreToolUse protocol blocks a tool call only on exit code 2; a raw
# exit 1 is treated as a non-blocking error. check-halt.sh uses exit 1 to mean
# "a halt is engaged", so this wrapper translates that into the blocking exit 2
# and surfaces the reason on stderr (shown to the agent).
#
# FAIL OPEN: any other outcome — halt clear (exit 0), a missing/broken check, or
# not-a-git-repo — exits 0 so the tool proceeds. A per-tool-call gate must never
# be able to wedge a session (or the ~40 concurrent worktree sessions) because
# the check itself failed. The halt is a cooperative operator stop, not a
# security boundary. See .opencode/rules/operator-halt.md.
set -uo pipefail

dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Global sentinel only (AGENT_HALT). Scoped sentinels (AGENT_HALT_commit, …) are
# checked by the specific gates that own them (commit-workflow, worktree merge).
out="$("$dir/check-halt.sh" 2>&1)"
rc=$?

if [ "$rc" -eq 1 ]; then
  printf '%s\n' "$out" >&2
  exit 2   # block the tool call
fi

exit 0     # clear, or the check could not run → fail open (allow)
