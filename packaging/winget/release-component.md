# Release component: winget

**Automated** — published by `scripts/release/components/winget.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step so the GitHub Release and its assets already exist.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| `mockserver-release/winget-github-token` | A GitHub PAT (field `token`) that can open PRs on `microsoft/winget-pkgs` from a fork |
| `wingetcreate` on PATH | Windows-only tool; PR submission only runs on a Windows agent |

When the secret is absent the component renders the manifest and exits 0, logging
`"<secret> not configured — skipping winget PR submission"`.
When `wingetcreate` is not available (non-Windows agent) it similarly skips the PR
submission but still renders the manifest. The release is never blocked.

## How it works

1. Fetches the SHA256 of the Windows bundle `mockserver-<version>-windows-x86_64.zip`
   from its `.sha256` sidecar on the GitHub Release.
2. Renders `packaging/winget/MockServer.MockServer.yaml` — substituting `${VERSION}`
   and `${SHA256_WINDOWS_X86_64}`.
3. Calls `wingetcreate update MockServer.MockServer --version <version> --urls <url> --submit --token <token>`
   to open a PR against `microsoft/winget-pkgs`.

The Windows bundle unpacks to `mockserver-<version>-windows-x86_64/` containing
`bin/mockserver.bat`, `lib/mockserver.jar`, and `runtime/` (a trimmed JVM).
winget installs the bundle and exposes `mockserver.bat` as `mockserver`.

winget is Windows-only; only the `windows-x86_64` bundle is consumed.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/winget.sh --dry-run
```

Resolves the checksum (renders a placeholder if the version does not exist), prints
the rendered manifest, and skips the PR submission. Does not require `wingetcreate`.

## Manual fallback

```bash
wingetcreate update MockServer.MockServer \
  --version <VERSION> \
  --urls https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>/mockserver-<VERSION>-windows-x86_64.zip \
  --submit --token <GITHUB_PAT>
```
