#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
errors=0

command -v python3 >/dev/null 2>&1 || {
  echo "ERROR: python3 is required"
  exit 1
}

agent_skill_enabled() {
  local agent_name="$1"
  python3 - "$repo_root/opencode.jsonc" "$agent_name" <<'PY'
import json, sys

path = sys.argv[1]
agent = sys.argv[2]
text = open(path, encoding="utf-8").read()

out = []
in_string = False
escape = False
i = 0
while i < len(text):
    ch = text[i]
    nxt = text[i + 1] if i + 1 < len(text) else ""
    if in_string:
        out.append(ch)
        if escape:
            escape = False
        elif ch == "\\":
            escape = True
        elif ch == '"':
            in_string = False
        i += 1
        continue
    if ch == '"':
        in_string = True
        out.append(ch)
        i += 1
        continue
    if ch == "/" and nxt == "/":
        i += 2
        while i < len(text) and text[i] != "\n":
            i += 1
        continue
    out.append(ch)
    i += 1

text = "".join(out)
config = json.loads(text)
agent_cfg = config.get("agent", {}).get(agent, {})
tools = agent_cfg.get("tools", {})
print("true" if tools.get("skill", True) else "false")
PY
}

echo "[opencode] validating command -> skill references"
for command_file in "$repo_root"/.opencode/commands/*.md; do
  while IFS= read -r skill_name; do
    [[ -z "$skill_name" ]] && continue
    if [[ ! -f "$repo_root/.opencode/skills/$skill_name/SKILL.md" ]]; then
      rel_command_file="${command_file#$repo_root/}"
      echo "ERROR: ${rel_command_file} references missing skill '$skill_name'"
      errors=1
    fi
  done < <(
    grep -Eo 'Load the `[a-z0-9-]+` skill' "$command_file" \
      | sed -E 's/Load the `([a-z0-9-]+)` skill/\1/'
  )
done

echo "[opencode] validating command -> agent references"
for command_file in "$repo_root"/.opencode/commands/*.md; do
  agent_name="$(grep -E '^agent:' "$command_file" | awk '{print $2}' || true)"
  [[ -z "$agent_name" ]] && continue
  if [[ "$agent_name" == "general" ]]; then
    continue
  fi
  if ! grep -q "\"$agent_name\"[[:space:]]*:" "$repo_root/opencode.jsonc"; then
    rel_command_file="${command_file#$repo_root/}"
    echo "ERROR: ${rel_command_file} references missing agent '$agent_name'"
    errors=1
  fi
done

echo "[opencode] validating agent prompt files exist"
while IFS= read -r agent_prompt_path; do
  [[ -z "$agent_prompt_path" ]] && continue
  if [[ ! -f "$repo_root/$agent_prompt_path" ]]; then
    echo "ERROR: missing agent prompt file '$agent_prompt_path'"
    errors=1
  fi
done < <(grep -Eo '"agent"[[:space:]]*:[[:space:]]*"\.opencode/agents/[a-z0-9-]+\.md"' "$repo_root/opencode.jsonc" \
  | sed -E 's/.*"(\.opencode\/agents\/[a-z0-9-]+\.md)"/\1/')

echo "[opencode] validating subagent-routed skills"
for command_file in "$repo_root"/.opencode/commands/*.md; do
  has_subtask="$(grep -E '^subtask:[[:space:]]*true$' "$command_file" || true)"
  [[ -z "$has_subtask" ]] && continue

  skill_name="$(grep -Eo 'Load the `[a-z0-9-]+` skill' "$command_file" | sed -E 's/Load the `([a-z0-9-]+)` skill/\1/' | head -n1 || true)"
  [[ -z "$skill_name" ]] && continue

  command_agent="$(grep -E '^agent:' "$command_file" | awk '{print $2}' || true)"
  rel_command_file="${command_file#$repo_root/}"

  if [[ -z "$command_agent" || "$command_agent" == "general" ]]; then
    echo "ERROR: ${rel_command_file} sets 'subtask: true' but has no explicit non-general agent"
    errors=1
  fi

  if [[ ! -f "$repo_root/.opencode/skills/$skill_name/SKILL.md" ]]; then
    echo "ERROR: ${rel_command_file} routes to missing skill '$skill_name'"
    errors=1
    continue
  fi

  if [[ "$(agent_skill_enabled "$command_agent")" != "true" ]]; then
    echo "ERROR: agent '$command_agent' must allow skill tool for subagent-routed skill '$skill_name'"
    errors=1
  fi
done

echo "[opencode] validating drift-prone infrastructure literals"
drift_files=(
  "AGENTS.md"
  ".opencode/skills/aws-investigation/SKILL.md"
  ".opencode/skills/terraform-tfvars/SKILL.md"
  ".opencode/skills/build-monitor/SKILL.md"
)

for drift_file in "${drift_files[@]}"; do
  full_path="$repo_root/$drift_file"

  if [[ ! -f "$full_path" ]]; then
    echo "WARN: ${drift_file} not found, skipping"
    continue
  fi

  if grep -nE 't3\.large|c5\.2xlarge|(^|[^[:alnum:]])[0-9]+([^0-9A-Za-z]+[0-9]+)?[[:space:]]+instances?|min_size[[:space:]]*=[[:space:]]*[1-9][0-9]*|max_size[[:space:]]*=[[:space:]]*[0-9]+|on_demand_percentage[[:space:]]*=[[:space:]]*[0-9]+' "$full_path" >/dev/null; then
    echo "ERROR: ${drift_file} contains hardcoded instance or capacity literals"
    grep -nE 't3\.large|c5\.2xlarge|(^|[^[:alnum:]])[0-9]+([^0-9A-Za-z]+[0-9]+)?[[:space:]]+instances?|min_size[[:space:]]*=[[:space:]]*[1-9][0-9]*|max_size[[:space:]]*=[[:space:]]*[0-9]+|on_demand_percentage[[:space:]]*=[[:space:]]*[0-9]+' "$full_path" || true
    errors=1
  fi
done

echo "[opencode] validating rule reachability (no orphaned rules)"
if ! python3 - "$repo_root" <<'PY'
import os, re, sys

root = sys.argv[1]
rules_dir = os.path.join(root, ".opencode", "rules")
if not os.path.isdir(rules_dir):
    sys.exit(0)

rules = {f[:-3] for f in os.listdir(rules_dir) if f.endswith(".md")}
if not rules:
    sys.exit(0)


def read(path):
    try:
        return open(path, encoding="utf-8").read()
    except OSError:
        return ""


# Entry docs are the always-loaded / independently-loadable files a rule can be
# discovered from: AGENTS.md (the only always-loaded instruction file), the
# agents/commands/skills that load when invoked, and opencode.jsonc.
entry_files = [
    os.path.join(root, "AGENTS.md"),
    os.path.join(root, "CLAUDE.md"),
    os.path.join(root, "opencode.jsonc"),
]
for base in (".opencode/agents", ".opencode/commands", ".opencode/skills",
             ".claude/agents", ".claude/commands"):
    for dirpath, _, filenames in os.walk(os.path.join(root, base)):
        entry_files += [os.path.join(dirpath, f) for f in filenames
                        if f.endswith(".md")]

rule_text = {r: read(os.path.join(rules_dir, r + ".md")) for r in rules}
# Whole-token match so "commit-workflow" never counts as a hit for "commit".
pattern = {r: re.compile(r"(?<![A-Za-z0-9_-])" + re.escape(r) + r"(?![A-Za-z0-9_-])")
           for r in rules}


def refs_in(text, exclude):
    return {r for r in rules if r != exclude and pattern[r].search(text)}


# BFS from the entry docs through rule -> rule references. A rule reachable only
# from another orphaned rule (a cycle of orphans) is never reached, so this
# catches more than a raw inbound-reference count would.
reachable = set()
frontier = set()
for entry in entry_files:
    frontier |= refs_in(read(entry), None)
reachable |= frontier
while frontier:
    nxt = set()
    for r in frontier:
        nxt |= refs_in(rule_text[r], r)
    nxt -= reachable
    reachable |= nxt
    frontier = nxt

orphans = sorted(rules - reachable)
for orphan in orphans:
    print("ERROR: rule .opencode/rules/%s.md is unreachable — no entry doc "
          "(AGENTS.md, an agent/command/skill, or opencode.jsonc) and no "
          "reachable rule references it; link it from a referrer or remove it"
          % orphan)
sys.exit(1 if orphans else 0)
PY
then
  errors=1
fi

if [[ "$errors" -ne 0 ]]; then
  echo "[opencode] validation failed"
  exit 1
fi

echo "[opencode] validation passed"
