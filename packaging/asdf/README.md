# asdf / mise -- MockServer CLI

asdf (and its Rust successor mise) is a version manager for multiple tools.
MockServer provides an asdf plugin so users can install and switch between
CLI versions with:

```bash
# asdf
asdf plugin add mockserver https://github.com/mock-server/asdf-mockserver
asdf install mockserver latest
asdf global mockserver latest

# mise (drop-in compatible)
mise plugin add mockserver https://github.com/mock-server/asdf-mockserver
mise install mockserver@latest
mise use mockserver@latest
```

## How it works

An asdf plugin is a repository containing executable scripts in `bin/`:

| Script | Purpose |
|--------|---------|
| `bin/list-all` | Lists all available versions (queries GitHub Releases API) |
| `bin/download` | Downloads a specific version binary |
| `bin/install` | Installs the downloaded binary into the versioned path |
| `bin/latest-stable` | Returns the latest stable version |

The plugin detects the user's OS and architecture automatically and downloads
the correct native binary from GitHub Releases.

### Plugin repository

The plugin is hosted at `mock-server/asdf-mockserver` on GitHub. The `bin/`
scripts in this directory are the content of that repository. During release,
the scripts are synced to the plugin repo.

## Publishing a new version

No per-release publish step is needed. The plugin `bin/` scripts query GitHub
Releases dynamically — once the GitHub Release for a version exists, asdf/mise
users can immediately install it.

The release component script `scripts/release/components/asdf.sh` keeps the
plugin repo (`mock-server/asdf-mockserver`) in sync with `packaging/asdf/bin/`
and verifies the new release is discoverable. See
[release-component.md](release-component.md) for prerequisites.

### One-time setup

1. Create the `mock-server/asdf-mockserver` repository on GitHub.
2. Push the `bin/` scripts from this directory.
3. Submit a PR to the asdf plugin index:
   https://github.com/asdf-vm/asdf-plugins (add a `mockserver` entry).

## Supported platforms

The `bin/download` script resolves the bundle for the current OS and architecture:

| OS | Architecture | Bundle archive |
|----|-------------|----------------|
| Linux | x86_64 | `mockserver-<version>-linux-x86_64.tar.gz` |
| Linux | aarch64 | `mockserver-<version>-linux-aarch64.tar.gz` |
| macOS | x86_64 | `mockserver-<version>-darwin-x86_64.tar.gz` |
| macOS | arm64 | `mockserver-<version>-darwin-aarch64.tar.gz` |
| Windows | x86_64 | `mockserver-<version>-windows-x86_64.zip` |

Each bundle is a self-contained archive — it includes a trimmed JVM, so no
separate Java installation is required.
