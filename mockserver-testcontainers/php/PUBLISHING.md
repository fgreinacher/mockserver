# Publishing mockserver-testcontainers to Packagist

Packagist requires `composer.json` at the **repository root** of the default
branch and does not support subdirectory packages. Because this module lives at
`mockserver-testcontainers/php/` inside the monorepo, it is published through a
read-only **subtree-split mirror repo** whose root IS the package — exactly the
mechanism used by the MockServer PHP client (`scripts/release/components/client-php.sh`):

```
github.com/mock-server/mockserver-testcontainers-php   (master = subtree split
                                                         of mockserver-testcontainers/php/)
```

The monorepo remains the single source of truth; the mirror is regenerated from
it at release time. Packagist has a webhook on the **mirror** repo, so pushing
`master` plus a version tag there triggers indexing within 1–2 minutes.

## One-time setup (per new package)

1. Create the public mirror repo `mock-server/mockserver-testcontainers-php`.
2. Submit it on Packagist.
3. Add the Packagist webhook to the **mirror** repo (not the monorepo).

## Release (automated)

`scripts/release/components/tc-php.sh` performs the subtree split and pushes the
mirror `master` + version tag (see `release-component.md`).

## Liveness verification

```bash
composer show mock-server/mockserver-testcontainers "${RELEASE_VERSION}" 2>/dev/null \
  || curl -sf "https://repo.packagist.org/p2/mock-server/mockserver-testcontainers.json" \
       | grep -q "\"version\":\"${RELEASE_VERSION}\""
```
