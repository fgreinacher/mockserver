# SDKMAN! -- MockServer CLI

SDKMAN! is a tool for managing parallel versions of SDKs on Unix-like systems
(and Windows via WSL/Git Bash). MockServer registers as a candidate so users
can install the CLI with:

```bash
sdk install mockserver
```

## How it works

SDKMAN! uses a Vendor API -- there are no manifest files to commit to an
external repository. Instead, the MockServer release pipeline calls the API
to register new versions. The API is documented at https://sdkman.io/vendors.

### One-time setup

Before the first release, the `mockserver` candidate must be registered with
the SDKMAN! team:

1. Open an issue at https://github.com/sdkman/sdkman-cli/issues requesting
   a new candidate.
2. Provide: candidate name (`mockserver`), description, website URL, whether
   it's platform-specific (yes -- native binaries).
3. Once approved, you receive vendor API credentials (consumer key + consumer
   token) which are stored in AWS Secrets Manager at
   `mockserver-release/sdkman-vendor`.

### Platform bundles

SDKMAN! supports platform-specific distributions. The release component registers
download URLs for each of the five self-contained bundle archives:

| SDKMAN! platform | Bundle archive |
|---|---|
| `LINUX_64` | `mockserver-<version>-linux-x86_64.tar.gz` |
| `LINUX_ARM64` | `mockserver-<version>-linux-aarch64.tar.gz` |
| `MAC_OSX` | `mockserver-<version>-darwin-x86_64.tar.gz` |
| `MAC_ARM64` | `mockserver-<version>-darwin-aarch64.tar.gz` |
| `WINDOWS_64` | `mockserver-<version>-windows-x86_64.zip` |

Each archive includes a trimmed JVM — no separate Java installation is required.
SDKMAN! installs the bundle and adds its `bin/` to `PATH`.

## Publishing a new version

Publishing is automated — see [release-component.md](release-component.md) for
how it works and the prerequisites to activate the channel.

The release component script `scripts/release/components/sdkman.sh`:

1. Calls `POST /release` on the Vendor API for each of the five platforms.
2. Calls `PUT /default` to set the new version as the default.
3. Calls `POST /announce/struct` to broadcast the release.

### Manual fallback

See [release-component.md](release-component.md) for the full `curl` commands.
