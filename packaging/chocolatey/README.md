# Chocolatey -- MockServer CLI

Chocolatey is a Windows package manager. MockServer publishes the CLI as a
Chocolatey package so users can install with:

```powershell
choco install mockserver
```

## How it works

Chocolatey packages are `.nupkg` files (NuGet format) containing:
- `mockserver.nuspec` -- package metadata
- `tools/chocolateyinstall.ps1` -- install script (downloads and unpacks the
  self-contained Windows bundle from the GitHub Release)
- `tools/chocolateyuninstall.ps1` -- uninstall script

Packages are pushed to the Chocolatey Community Repository at
https://community.chocolatey.org using `choco push`.

## Publishing a new version

Publishing is automated — see [release-component.md](release-component.md) for
how it works and the prerequisites to activate the channel.

The release component script `scripts/release/components/chocolatey.sh`:

1. Fetches the SHA256 of `mockserver-<version>-windows-x86_64.zip` from its
   `.sha256` sidecar on the GitHub Release.
2. Renders `mockserver.nuspec` (version) and `tools/chocolateyinstall.ps1` (checksum).
3. Runs `choco pack` to build the `.nupkg`.
4. Runs `choco push` to upload to `community.chocolatey.org`.

The install script downloads `mockserver-<version>-windows-x86_64.zip`, verifies
its SHA256, and installs the self-contained bundle (a trimmed JVM is included —
no separate Java installation is required).

### Prerequisites

- `choco` CLI on a Windows (or mono) agent
- A Chocolatey API key (stored in `mockserver-release/chocolatey-api-key` in
  AWS Secrets Manager)

### Manual fallback

```powershell
# Render .nuspec and tools/*.ps1 (substitute version + SHA256), then:
choco pack .\mockserver.nuspec
choco push mockserver.<VERSION>.nupkg --source https://push.chocolatey.org/ --api-key <KEY>
```

## Moderation

Chocolatey Community Repository submissions go through automated and manual
moderation. First submissions take longer (1–2 weeks). Subsequent versions
with no structural changes are typically auto-approved.
