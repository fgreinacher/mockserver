# winget -- MockServer CLI

winget is the Windows Package Manager. MockServer publishes the CLI as a
winget package under the identifier `MockServer.MockServer`, installing the
self-contained Windows bundle (includes a trimmed JVM — no separate Java required).

## How it works

winget manifests live in the community repository
[microsoft/winget-pkgs](https://github.com/microsoft/winget-pkgs). Each
version is a YAML manifest file at:

```
manifests/m/MockServer/MockServer/<VERSION>/MockServer.MockServer.yaml
```

Publishing a new version means opening a PR to that repo with the updated
manifest. The `wingetcreate` CLI automates this.

## Publishing a new version

Publishing is automated — see [release-component.md](release-component.md) for
how it works and the prerequisites to activate the channel.

The release component script `scripts/release/components/winget.sh`:

1. Fetches the SHA256 of `mockserver-<version>-windows-x86_64.zip` from its
   `.sha256` sidecar on the GitHub Release.
2. Renders `MockServer.MockServer.yaml` with the new version and checksum.
3. Calls `wingetcreate update` to submit a PR to `microsoft/winget-pkgs`.

### Prerequisites

- `wingetcreate` CLI (Windows-only; the step is skipped on non-Windows agents)
- A GitHub PAT with `public_repo` scope (stored in
  `mockserver-release/winget-github-token` in AWS Secrets Manager)

### Manual fallback

```bash
wingetcreate update MockServer.MockServer \
  --version <VERSION> \
  --urls https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>/mockserver-<VERSION>-windows-x86_64.zip \
  --submit --token <GITHUB_PAT>
```

## Manifest format

The `MockServer.MockServer.yaml` file in this directory is the template/reference
manifest. It uses the winget singleton format (v1.9.0) with a single x64 installer
entry pointing at the self-contained bundle archive.
