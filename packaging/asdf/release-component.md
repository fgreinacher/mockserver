# Release component: asdf / mise

**Automated** — maintained by `scripts/release/components/asdf.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step.

Unlike the other package-manager channels, there is **no per-release publish step**:
the asdf/mise plugin downloads bundles directly from the GitHub Release at
`asdf install` time. The release component's job is to keep the plugin repo in sync
with the scripts in `packaging/asdf/bin/` and to verify the new release is
discoverable.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| Plugin repo | `github.com/mock-server/asdf-mockserver` must exist |
| Write access | Repo must be writable by `mockserver-release/github-token` (field `token`) |
| One-time registration | Submit a PR to https://github.com/asdf-vm/asdf-plugins to list `mockserver` in the plugin index |

When the plugin repo is not reachable the component exits 0 and logs
`"asdf plugin repo mock-server/asdf-mockserver not reachable — skipping"`.
The release is never blocked.

## How it works

1. **Plugin repo sync** — clones `mock-server/asdf-mockserver`, diffs each of the
   four `bin/` scripts (`list-all`, `download`, `install`, `latest-stable`) against
   the source of truth in `packaging/asdf/bin/`. If any differ, copies and pushes
   a `"sync plugin scripts from mockserver-monorepo (<version>)"` commit.
2. **Discoverability check** — queries the GitHub Releases API for
   `mockserver-<version>` and fails the step (non-zero exit) if the tag is not
   found, so a missing release surfaces immediately.

The `bin/download` script in `packaging/asdf/bin/` resolves the correct bundle for
the current OS and architecture:

| OS | Arch | Bundle |
|----|------|--------|
| Linux | x86_64 | `mockserver-<version>-linux-x86_64.tar.gz` |
| Linux | aarch64 | `mockserver-<version>-linux-aarch64.tar.gz` |
| macOS | x86_64 | `mockserver-<version>-darwin-x86_64.tar.gz` |
| macOS | arm64 | `mockserver-<version>-darwin-aarch64.tar.gz` |
| Windows | x86_64 | `mockserver-<version>-windows-x86_64.zip` |

Each bundle unpacks to a top-level directory containing `bin/mockserver`,
`lib/mockserver.jar`, and `runtime/` (a trimmed JVM). `bin/install` exposes
the launcher via asdf's shim mechanism.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/asdf.sh --dry-run
```

Reports what would be synced/verified; skips the clone and push. The discoverability
check is also skipped in dry-run (the fake version won't exist on GitHub).
