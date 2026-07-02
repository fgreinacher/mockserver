# Release component: SDKMAN!

**Automated** — published by `scripts/release/components/sdkman.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step so the GitHub Release and its assets already exist.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| `mockserver-release/sdkman-vendor` | SDKMAN! vendor credentials — fields `consumer-key` and `consumer-token` |
| One-time candidate registration | `mockserver` must be registered as a SDKMAN! candidate before the first publish (open an issue at https://github.com/sdkman/sdkman-cli) |

When the secret is absent the component exits 0 and logs
`"<secret> not configured — skipping SDKMAN!"`. The release is never blocked.

## How it works

1. Confirms the five platform bundles are present on the GitHub Release (exits if
   any is missing in execute mode).
2. Calls `POST /release` on the SDKMAN! Vendor API for each of the five platform bundles:

   | SDKMAN! platform | Bundle |
   |---|---|
   | `LINUX_64` | `mockserver-<version>-linux-x86_64.tar.gz` |
   | `LINUX_ARM64` | `mockserver-<version>-linux-aarch64.tar.gz` |
   | `MAC_OSX` | `mockserver-<version>-darwin-x86_64.tar.gz` |
   | `MAC_ARM64` | `mockserver-<version>-darwin-aarch64.tar.gz` |
   | `WINDOWS_64` | `mockserver-<version>-windows-x86_64.zip` |

3. Calls `PUT /default` to set the new version as the default candidate.
4. Calls `POST /announce/struct` to broadcast the release on the SDKMAN! broadcast channel.

Each bundle is a self-contained archive containing `bin/mockserver` (or `bin/mockserver.bat`),
`lib/mockserver.jar`, and `runtime/` (a trimmed JVM). SDKMAN! installs the archive and
adds its `bin/` to `PATH`.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/sdkman.sh --dry-run
```

Prints the Vendor API calls that would be made (with placeholder credentials) and
skips all HTTP requests. Does not require AWS access.

## Manual fallback

```bash
SDKMAN_KEY=<key>
SDKMAN_TOKEN=<token>
BASE="https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-<VERSION>"

# Register each platform (repeat for all five):
curl -fsSL -X POST https://vendors.sdkman.io/release \
  -H "Consumer-Key: $SDKMAN_KEY" -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"candidate":"mockserver","version":"<VERSION>","platform":"LINUX_64","url":"'"$BASE/mockserver-<VERSION>-linux-x86_64.tar.gz"'"}'

# Set default:
curl -fsSL -X PUT https://vendors.sdkman.io/default \
  -H "Consumer-Key: $SDKMAN_KEY" -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"candidate":"mockserver","version":"<VERSION>"}'

# Announce:
curl -fsSL -X POST https://vendors.sdkman.io/announce/struct \
  -H "Consumer-Key: $SDKMAN_KEY" -H "Consumer-Token: $SDKMAN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"candidate":"mockserver","version":"<VERSION>","hashtag":"mockserver"}'
```
