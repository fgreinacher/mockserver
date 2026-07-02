# Release Component: mockserver-testcontainers (PHP)

## `scripts/release/components/tc-php.sh`

Publishes the module to Packagist via a subtree-split mirror repo, mirroring
`client-php.sh`. Packagist does not support subdirectory packages, so the module
at `mockserver-testcontainers/php/` is split to a mirror repo whose root is the
package. The default image tag is derived at runtime from the installed
`mock-server/mockserver-client` version, so no source constant needs a `sed`
bump for the image.

```bash
#!/usr/bin/env bash
set -euo pipefail

VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"
SUBDIR="mockserver-testcontainers/php"
MIRROR="git@github.com:mock-server/mockserver-testcontainers-php.git"

# Subtree-split the module subdirectory to a temp branch, then push the mirror
# master + tag (git-subtree runs inside the pinned Maven image, as in client-php.sh).
SPLIT_SHA=$(git subtree split --prefix="${SUBDIR}" HEAD)
git push "${MIRROR}" "${SPLIT_SHA}:refs/heads/master" --force
git push "${MIRROR}" "${SPLIT_SHA}:refs/tags/${VERSION}"
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# mockserver-testcontainers (Packagist)
curl -sf "https://repo.packagist.org/p2/mock-server/mockserver-testcontainers.json" \
  | grep -q "\"version\":\"${RELEASE_VERSION}\""
```

## Registration

Add `tc-php` to the `COMPONENTS` list in `scripts/release/release.sh`, a verify
entry in `scripts/release/components/verify.sh`, and a publish step in
`.buildkite/release-pipeline.yml` (mirroring the `client-php` step). These
pipeline/orchestration files, plus the one-time Packagist mirror-repo + webhook
setup, are control-plane changes and require gated review. See `PUBLISHING.md`.
```
