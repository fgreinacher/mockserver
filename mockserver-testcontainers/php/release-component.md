# Release Component: mockserver-testcontainers (PHP)

> **Status: AUTOMATED (pending one-time mirror-repo provisioning).** Wired into
> the release pipeline as the `tc-php` component
> (`scripts/release/release.sh` `ALL_COMPONENTS`, a `soft_fail` step in
> `.buildkite/release-pipeline.yml`, and a soft check in
> `scripts/release/components/verify.sh`).
>
> **Prerequisites (one-time, see `PUBLISHING.md`):**
> 1. Create the public mirror repo `mock-server/mockserver-testcontainers-php`.
> 2. Submit it on Packagist.
> 3. Add the Packagist webhook to the **mirror** repo (not the monorepo).
>
> Until the mirror repo exists and is reachable, the component skips gracefully
> (exit 0) — it never blocks the release. Pushes use the release
> `mockserver-release/github-token` via the F-BK-03-safe `http.extraheader`
> (`configure_git_for_push`), cleared on every exit path; no token is ever
> written to disk.

## `scripts/release/components/tc-php.sh`

Publishes the module to Packagist via a subtree-split mirror repo, mirroring
`client-php.sh`. Packagist does not support subdirectory packages, so the module
at `mockserver-testcontainers/php/` is split to a mirror repo whose root is the
package. The default image tag is derived at runtime from the installed
`mock-server/mockserver-client` version, so no source constant needs a `sed`
bump for the image.

The reference snippet below is illustrative; the shipped script also validates
`composer.json`, graceful-skips until the mirror repo is provisioned, runs the
`git subtree split` inside the pinned `$MAVEN_IMAGE` (the release-agent host git
lacks the `subtree` subcommand), wraps each push in `retry`, and tolerates
Packagist indexing lag with a `:warning:`.

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

## Registration (done)

`tc-php` is registered in the `ALL_COMPONENTS` list in
`scripts/release/release.sh`, has a soft verify entry in
`scripts/release/components/verify.sh`, and a `soft_fail` publish step in
`.buildkite/release-pipeline.yml` (mirroring the `client-php` step). These
pipeline/orchestration files were changed under gated review. The one-time
Packagist mirror-repo + webhook setup is still required before the first real
publish — see `PUBLISHING.md`.
```
