# Release component: Scoop

**Automated** — published by `scripts/release/components/scoop.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step so the GitHub Release and its assets already exist.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| Companion bucket repo | `github.com/mock-server/scoop-mockserver` must exist |
| Write access | Repo must be writable by `mockserver-release/github-token` (field `token`) |

When either is absent the component exits 0 and logs
`"Scoop bucket repo mock-server/scoop-mockserver not reachable — skipping"`.
The release is never blocked.

## How it works

1. Fetches the SHA256 of the Windows bundle `mockserver-<version>-windows-x86_64.zip`
   from its `.sha256` sidecar on the GitHub Release (the format `binary.sh` publishes).
2. Renders `packaging/scoop/mockserver.json` — substituting `${VERSION}` and
   `${SHA256_WINDOWS_X86_64}`.
3. Clones `mock-server/scoop-mockserver`, copies the rendered manifest, commits as
   `mockserver <version>`, and pushes.

The Windows bundle is a self-contained archive that unpacks to
`mockserver-<version>-windows-x86_64/` containing `bin/mockserver.bat`, `lib/mockserver.jar`,
and `runtime/` (a trimmed JVM — no separate Java installation required). Scoop shims
`bin/mockserver.bat` onto `PATH`.

Scoop channels are Windows-only; only the `windows-x86_64` bundle is consumed.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/scoop.sh --dry-run
```

Resolves the checksum (renders a placeholder if the version does not exist), prints
the rendered manifest, and skips the bucket push.

## Manual fallback

If the Buildkite step fails after the GitHub Release exists:

```bash
# Clone the bucket, copy the rendered manifest from the dry-run output, push
git clone https://github.com/mock-server/scoop-mockserver
cd scoop-mockserver
# paste rendered mockserver.json
git commit -am "mockserver <VERSION>"
git push
```
