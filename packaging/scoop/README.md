# Scoop -- MockServer CLI

Scoop is a command-line installer for Windows. MockServer publishes a Scoop
manifest so users can install the CLI with:

```powershell
scoop bucket add mockserver https://github.com/mock-server/scoop-mockserver
scoop install mockserver
```

## How it works

Scoop manifests are JSON files hosted in a "bucket" repository. MockServer uses
a dedicated bucket repo at `mock-server/scoop-mockserver` on GitHub. The
manifest file is `mockserver.json` (this directory contains the template).

### Autoupdate

The manifest includes a `checkver` block (pointing at the GitHub repo's releases)
and an `autoupdate` block with URL templates. This means Scoop's automated
tooling (`scoop-checkver`) can detect new releases and generate updated manifests
with near-zero manual effort.

The `autoupdate.hash.url` assumes each binary has a companion `.sha256` sidecar
file at the same URL. If the CLI release uses a different checksum scheme (e.g.
a single `checksums.txt`), adjust the hash URL pattern accordingly.

## Publishing a new version

Publishing is automated — see [release-component.md](release-component.md) for
how it works and the prerequisites to activate the channel.

The release component script `scripts/release/components/scoop.sh`:

1. Fetches the SHA256 of `mockserver-<version>-windows-x86_64.zip` from its
   `.sha256` sidecar on the GitHub Release.
2. Renders `mockserver.json` with the new version and checksum.
3. Pushes the updated manifest to the `mock-server/scoop-mockserver` bucket repo.

### Prerequisites

- A GitHub token with push access to `mock-server/scoop-mockserver` (stored in
  `mockserver-release/github-token`)
- The `mock-server/scoop-mockserver` bucket repository must exist on GitHub

### Manual fallback

```bash
# Clone the bucket, update the manifest, push
git clone https://github.com/mock-server/scoop-mockserver
cd scoop-mockserver
# Edit mockserver.json with new version/URLs/hashes
git commit -am "mockserver <VERSION>"
git push
```
