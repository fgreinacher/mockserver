#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

DOCKERFILES=(
  "docker/Dockerfile"
  "docker/snapshot/Dockerfile"
  "docker/root/Dockerfile"
  "docker/root-snapshot/Dockerfile"
  "docker/local/Dockerfile"
  "docker/graaljs/Dockerfile"
  "docker/clustered/Dockerfile"
  "docker/aot/Dockerfile"
)

errors=0

for df in "${DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  if [ ! -f "$filepath" ]; then
    echo "WARN: $df not found, skipping"
    continue
  fi

  if grep -qE 'CMD\s+\["-serverPort"' "$filepath"; then
    echo "FAIL: $df uses CMD [\"-serverPort\", ...] — must use ENV SERVER_PORT + CMD [] instead"
    errors=$((errors + 1))
  fi

  # Accept only the modern "ENV SERVER_PORT=1080" form. The legacy space-separated
  # "ENV SERVER_PORT 1080" is what BuildKit's LegacyKeyValueFormat lint flags, so the
  # Dockerfiles were migrated to "=" and this gate now asserts the migrated form (a bare
  # space would be a regression back to the deprecated syntax).
  if ! grep -qE 'ENV\s+SERVER_PORT=1080' "$filepath"; then
    echo "FAIL: $df missing 'ENV SERVER_PORT=1080'"
    errors=$((errors + 1))
  fi

  if ! grep -qE 'CMD\s+\[\s*\]' "$filepath"; then
    echo "FAIL: $df missing 'CMD []'"
    errors=$((errors + 1))
  fi

  if ! grep -q 'org.mockserver.cli.Main' "$filepath"; then
    echo "FAIL: $df missing 'org.mockserver.cli.Main' in ENTRYPOINT"
    errors=$((errors + 1))
  fi

  # Every image that runs org.mockserver.cli.Main must cap the JVM heap so the in-memory
  # request/expectation rings size off a bounded heap, otherwise the container is liable to be
  # OOM-SIGKILLed under load. The cap is 50%: under load ZGC grows to its full heap in unreclaimable
  # memory and native JVM + kernel socket memory need the rest, so 60% was OOM-killed at 512 MiB.
  # GraalJS is 45%: its larger non-heap footprint left too little headroom at 50%.
  # Assert the cap so it cannot drift in one variant (the docs promise these exact values).
  expected_pct="50.0"
  [ "$df" = "docker/graaljs/Dockerfile" ] && expected_pct="45.0"
  if grep -q 'org.mockserver.cli.Main' "$filepath"; then
    entrypoint_pcts="$(grep -oE '"-XX:MaxRAMPercentage=[0-9.]+"' "$filepath" || true)"
    if [ "$entrypoint_pcts" != "\"-XX:MaxRAMPercentage=${expected_pct}\"" ]; then
      echo "FAIL: $df must set exactly one '-XX:MaxRAMPercentage=${expected_pct}' heap cap in ENTRYPOINT (found: ${entrypoint_pcts:-none})"
      errors=$((errors + 1))
    fi
  fi
done

# The image HEALTHCHECK must be the bundled static probe, never a second JVM: a probe JVM runs inside
# the container's memory cgroup every interval and pushed a 512 MiB container over its limit under
# load. Each image context carries a byte-identical copy of the canonical probe source.
PROBE_SOURCE="$REPO_ROOT/docker/healthcheck/mockserver-healthcheck.go"
for df in "${DOCKERFILES[@]}"; do
  filepath="$REPO_ROOT/$df"
  [ -f "$filepath" ] || continue
  if ! grep -qE '^  CMD \["/mockserver-healthcheck"\]$' "$filepath" \
     || ! grep -qE '^COPY --from=healthcheck /mockserver-healthcheck /mockserver-healthcheck$' "$filepath"; then
    echo "FAIL: $df HEALTHCHECK must run the bundled /mockserver-healthcheck probe (COPY --from=healthcheck + CMD [\"/mockserver-healthcheck\"])"
    errors=$((errors + 1))
  fi
  healthcheck_block="$(grep -A1 '^HEALTHCHECK' "$filepath" || true)"
  if grep -q 'java' <<<"$healthcheck_block"; then
    echo "FAIL: $df HEALTHCHECK starts a JVM — use the bundled /mockserver-healthcheck probe"
    errors=$((errors + 1))
  fi
  context_dir="$(dirname "$filepath")"
  if [ "$df" != "docker/Dockerfile" ] && ! cmp -s "$PROBE_SOURCE" "$context_dir/mockserver-healthcheck.go"; then
    echo "FAIL: $(dirname "$df")/mockserver-healthcheck.go is missing or differs from docker/healthcheck/mockserver-healthcheck.go"
    errors=$((errors + 1))
  fi
done

# Every image compiles the probe from a digest-pinned golang image (hard check). They should all
# pin the SAME one so scanners see one Go stdlib version; Dependabot's healthcheck-golang group
# bumps them in one PR, but a partial bump is only warned about so it cannot wedge that PR red.
golang_froms=""
for df in "${DOCKERFILES[@]}"; do
  pinned="$(grep -E '^FROM golang:[^ ]+@sha256:[0-9a-f]{64} AS healthcheck$' "$REPO_ROOT/$df" || true)"
  pinned_count="$(grep -c . <<<"$pinned" || true)"
  if [ "$pinned_count" -ne 1 ]; then
    echo "FAIL: $df must have exactly one digest-pinned 'FROM golang:<tag>@sha256:<digest> AS healthcheck' (found $pinned_count)"
    errors=$((errors + 1))
  fi
  golang_froms="${golang_froms}${pinned}"$'\n'
done
golang_distinct="$(sort -u <<<"$golang_froms" | grep -c . || true)"
if [ "$golang_distinct" -gt 1 ]; then
  echo "WARNING: the ${#DOCKERFILES[@]} Dockerfiles pin $golang_distinct different golang images for the healthcheck stage — bring them to one digest (docs/infrastructure/docker.md, Docker HEALTHCHECK)"
fi

if [ $errors -gt 0 ]; then
  echo ""
  echo "FAILED: $errors Dockerfile sync issue(s) found"
  echo "All Dockerfiles must use: ENV SERVER_PORT=1080 + CMD [] (not CMD [\"-serverPort\", ...])"
  exit 1
fi

echo "PASSED: All Dockerfiles are in sync"
