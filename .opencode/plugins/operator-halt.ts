import type { Plugin } from "@opencode-ai/plugin"

// Mechanical enforcement of the operator halt (kill-switch) for the opencode
// harness. Mirrors the Claude Code PreToolUse hook (.opencode/scripts/
// check-halt-hook.sh); both call the same source of truth, check-halt.sh.
// See .opencode/rules/operator-halt.md.
//
// Only tools through which "new work" happens are gated: commits/pushes/external
// calls (bash), file mutation (write/edit/patch), and spawning subagents (task).
// Read-only tools are intentionally not gated — pausing investigation adds no
// safety and only raises the risk of wedging a session.
const GATED_TOOLS = new Set(["bash", "write", "edit", "patch", "task"])

export const OperatorHalt: Plugin = async ({ $, worktree }) => {
  return {
    "tool.execute.before": async (input) => {
      if (!GATED_TOOLS.has(input.tool)) return

      let haltReason: string | null = null
      try {
        // check-halt.sh resolves the sentinel at the main-checkout root via the
        // shared git common dir, so this works the same from any worktree.
        const res = await $`bash ${worktree}/.opencode/scripts/check-halt.sh`
          .quiet()
          .nothrow()
        if (res.exitCode === 1) {
          haltReason =
            res.stdout.toString().trim() ||
            "operator halt engaged — AI SDLC activity is paused; clear AGENT_HALT to resume (.opencode/rules/operator-halt.md)"
        }
      } catch {
        // The check itself failed to run — FAIL OPEN. A broken check must never
        // wedge every opencode session. Only an explicitly engaged halt blocks.
        return
      }

      // Throwing here denies the tool execution (the halt is engaged).
      if (haltReason) throw new Error(haltReason)
    },
  }
}
