# Release component: Chocolatey

**Automated** — published by `scripts/release/components/chocolatey.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step so the GitHub Release and its assets already exist.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| `mockserver-release/chocolatey-api-key` | The Chocolatey Community Repository push API key (field `key`) |
| `choco` on PATH | Windows (or mono) only; `choco pack` and `choco push` run on a Windows agent |

When the secret is absent the component renders the package files and exits 0,
logging `"<secret> not configured — skipping Chocolatey push"`.
When `choco` is not available the component similarly skips the push.
The release is never blocked.

**One-time setup:** The `mockserver` package must be submitted to the Chocolatey
Community Repository for the first time before moderation can auto-approve subsequent
versions. First submissions take 1–2 weeks.

## How it works

1. Fetches the SHA256 of the Windows bundle `mockserver-<version>-windows-x86_64.zip`
   from its `.sha256` sidecar on the GitHub Release.
2. Renders `packaging/chocolatey/mockserver.nuspec` (substituting `${VERSION}`) and
   `packaging/chocolatey/tools/chocolateyinstall.ps1` (substituting `${SHA256_WINDOWS_X86_64}`).
3. Copies `tools/chocolateyuninstall.ps1` unchanged.
4. Runs `choco pack` in the work directory to produce `mockserver.<version>.nupkg`.
5. Runs `choco push <nupkg> --source https://push.chocolatey.org/ --api-key <key>`.

The install script downloads `mockserver-<version>-windows-x86_64.zip` from the
GitHub Release, verifies its SHA256, and installs the self-contained bundle
(including `bin/mockserver.bat` and a trimmed JVM). No separate Java installation
is required.

Chocolatey is Windows-only; only the `windows-x86_64` bundle is consumed.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/chocolatey.sh --dry-run
```

Resolves the checksum (renders a placeholder if the version does not exist), prints
the rendered nuspec and install script, and skips `choco pack` and `choco push`.

## Manual fallback

```powershell
# Render packaging/chocolatey/mockserver.nuspec + tools/*.ps1 by hand (substitute
# version and SHA256), then:
choco pack .\mockserver.nuspec
choco push mockserver.<VERSION>.nupkg --source https://push.chocolatey.org/ --api-key <KEY>
```
