#!/usr/bin/env bash
#
# Fails the build if any agent-run step in .buildkite/pipeline-*.yml lacks
# `timeout_in_minutes`.
#
# WHY THIS IS A GATE AND NOT A CONVENTION
# ---------------------------------------
# The dispatcher emits NATIVE Buildkite `trigger` steps on push builds
# (generate-pipeline.sh). A native trigger step occupies no agent - which is the
# whole point, it is what keeps the small `trigger` queue from saturating - but
# Buildkite REJECTS `timeout_in_minutes` on a trigger step (HTTP 422 at config
# validation; it fails the pipeline upload outright). So there is NO parent-side
# wall-clock bound on how long a dispatcher build waits for a child.
#
# What bounds it instead is each child step's own timeout. That makes a property
# the pipeline YAML must hold into a safety requirement of the dispatcher, and a
# requirement enforced only by a comment asking people to keep it is not enforced
# at all - a new step without a timeout would widen the parent's wait silently,
# with nothing turning red. Hence this guard.
#
# Steps that cannot carry a timeout are deliberately NOT flagged: `wait`,
# `block`, `input`, `group` and `trigger` are not agent-run work.
#
# DETECTION LIMIT, stated so a green run is not over-read. This walks step blocks
# textually (PyYAML is not installed on the agents) and recognises a step by the
# key it leads with, against an explicit list. Broadening that to "any `- ` list
# item" would false-positive on plugin entries such as `- junit-annotate#<sha>:`,
# and a gate that cries wolf gets disabled - so the narrow miss is the chosen
# trade-off. A step leading with a key outside the list is therefore not seen,
# and neither is anything before the first recognised step in a file. Every step
# in this repo leads with a listed key; if that ever stops being true, widen the
# list rather than loosening the match.
#
# Known gaps this guard does NOT cover, recorded so nobody mistakes a green run
# here for "everything is bounded": the Terraform-defined bootstrap step every
# child pipeline runs (`buildkite-agent pipeline upload`, defined in
# terraform/buildkite-pipelines/pipelines.tf) carries no timeout, and the agent
# checkout/bootstrap phase is not a step and cannot carry one.
set -euo pipefail

cd "$(dirname "$0")/../../.."

FOUND=0

for file in .buildkite/pipeline-*.yml; do
  [ -e "$file" ] || continue
  # Stdlib only - PyYAML is not installed on the build agents, so this walks the
  # step blocks textually rather than parsing YAML.
  if ! python3 - "$file" <<'PY'
import re
import sys

path = sys.argv[1]
lines = open(path, encoding="utf-8").read().split("\n")

# A step begins at a list item introducing one of these keys.
# A step may legitimately lead with any of its own keys, not just `label`. This
# list is deliberately explicit rather than "any `- ` list item": a bare list
# item also matches plugin entries (`- junit-annotate#<sha>:`) and other nested
# arrays, and false-positives are a worse failure in a gate than a narrow miss.
STEP_START = re.compile(
    r'^(\s*)- (label|command|commands|plugins|key|id|if|depends_on|env|matrix|'
    r'agents|timeout_in_minutes|wait|block|input|group|trigger|skip|soft_fail|'
    r'parallelism|artifact_paths|retry|concurrency|concurrency_group|notify):'
)
# These are not agent-run work and cannot carry timeout_in_minutes.
NOT_AGENT_RUN = ("wait", "block", "input", "group", "trigger")

starts = [i for i, line in enumerate(lines) if STEP_START.match(line)]
starts.append(len(lines))

bad = []
for begin, end in zip(starts, starts[1:]):
    match = STEP_START.match(lines[begin])
    indent, key = match.group(1), match.group(2)
    block = lines[begin:end]
    # A `- label:` step whose body declares wait/block/group/trigger is also not
    # agent-run; check the whole block, not just its first line.
    # Only a key at the step's OWN indent counts. Searching the whole block at any
    # depth misclassifies an agent-run step whose command body happens to contain
    # a line like `trigger: something` (a heredoc emitting YAML, for instance).
    sibling = re.compile(r'^%s  (wait|block|input|group|trigger):' % re.escape(indent))
    body_keys = [l for l in block if sibling.match(l)]
    if key in NOT_AGENT_RUN or body_keys:
        continue
    if not any(re.match(r'^\s+timeout_in_minutes:', line) for line in block):
        label = next(
            (l.split(":", 1)[1].strip() for l in block if re.match(r'^\s+- ?label:|^\s*- label:', l)),
            lines[begin].strip(),
        )
        bad.append((begin + 1, label[:70]))

if bad:
    for line_no, label in bad:
        print("  %s:%d  missing timeout_in_minutes: %s" % (path, line_no, label))
    sys.exit(1)
PY
  then
    FOUND=1
  fi
done

if [ "$FOUND" -ne 0 ]; then
  cat >&2 <<'MSG'

Every agent-run step in .buildkite/pipeline-*.yml must declare timeout_in_minutes.

Push builds dispatch children via NATIVE Buildkite trigger steps, which cannot carry
timeout_in_minutes (Buildkite rejects it with a 422 and fails the pipeline upload). The
parent dispatcher therefore has no wall-clock bound of its own - each child step's own
timeout is what stops a wedged child from making the parent wait forever.

Add a timeout_in_minutes to the step(s) listed above. Pick a value comfortably above the
step's normal runtime: a timeout that fires on healthy work is worse than none.
MSG
  exit 1
fi

echo "All agent-run steps in .buildkite/pipeline-*.yml declare timeout_in_minutes"
