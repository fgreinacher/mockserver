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
  # OOM-SIGKILLed under load. The cap is 60% (not 75%): a committed heap costs ~1.48x its size in
  # real RSS, so 75% OOM-killed a 2 GiB container. Assert the cap is present so it cannot silently
  # drift back out of one variant (the consumer docs promise "the image caps the JVM heap at 60%").
  if grep -q 'org.mockserver.cli.Main' "$filepath" \
     && ! grep -qE '"-XX:MaxRAMPercentage=60\.0"' "$filepath"; then
    echo "FAIL: $df runs org.mockserver.cli.Main but is missing '-XX:MaxRAMPercentage=60.0' heap cap in ENTRYPOINT"
    errors=$((errors + 1))
  fi
done

if [ $errors -gt 0 ]; then
  echo ""
  echo "FAILED: $errors Dockerfile sync issue(s) found"
  echo "All Dockerfiles must use: ENV SERVER_PORT=1080 + CMD [] (not CMD [\"-serverPort\", ...])"
  exit 1
fi

echo "PASSED: All Dockerfiles are in sync"
