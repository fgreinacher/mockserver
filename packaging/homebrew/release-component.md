# Release component: Homebrew tap

**Automated** — published by `scripts/release/components/homebrew.sh` as a `soft_fail`
step in the `:package: Package Managers` Buildkite group, which runs after the
Binary Bundles step so the GitHub Release and its assets already exist.

> **This tap formula (`brew install mock-server/tap/mockserver`) is distinct from
> the JAR-based `mockserver` formula in `Homebrew/homebrew-core`** (bumped automatically
> by BrewTestBot from Maven Central — see `docs/operations/release-process.md` §9).
> The two are complementary: the homebrew-core formula depends on an OpenJDK
> installation; this tap formula is a self-contained bundle that requires nothing extra.

## Status / prerequisites to activate

| Prerequisite | Detail |
|---|---|
| Tap repo | `github.com/mock-server/homebrew-tap` must exist |
| Write access | Repo must be writable by `mockserver-release/github-token` (field `token`) |

When the tap repo is not reachable the component exits 0 and logs
`"Homebrew tap repo mock-server/homebrew-tap not reachable — skipping"`.
The release is never blocked.

## How it works

1. Fetches the SHA256 of each of the four macOS/Linux bundles from their `.sha256`
   sidecars on the GitHub Release:

   | Formula placeholder | Bundle |
   |---|---|
   | `${SHA256_DARWIN_AARCH64}` | `mockserver-<version>-darwin-aarch64.tar.gz` |
   | `${SHA256_DARWIN_X86_64}` | `mockserver-<version>-darwin-x86_64.tar.gz` |
   | `${SHA256_LINUX_AARCH64}` | `mockserver-<version>-linux-aarch64.tar.gz` |
   | `${SHA256_LINUX_X86_64}` | `mockserver-<version>-linux-x86_64.tar.gz` |

2. Renders `packaging/homebrew/mockserver.rb` — substituting `${VERSION}` and all
   four `${SHA256_*}` placeholders with the real checksums.
3. Clones `mock-server/homebrew-tap`, writes `Formula/mockserver.rb`, commits as
   `mockserver <version>`, and pushes.

The formula installs the self-contained bundle under `libexec` (containing
`bin/mockserver`, `lib/mockserver.jar`, and `runtime/`) and wraps the launcher with
`write_env_script` so the trimmed JVM resolves correctly. No separate Java
installation is required.

Homebrew is macOS/Linux only (no Windows platform); the Windows bundle is not
consumed by this component.

## Local dry-run

```bash
RELEASE_VERSION=99.99.0 ./scripts/release/components/homebrew.sh --dry-run
```

Resolves checksums (renders placeholders if the version does not exist), prints the
rendered formula, and skips the tap push.

## Manual fallback

If the Buildkite step fails after the GitHub Release exists:

```bash
git clone https://github.com/mock-server/homebrew-tap
mkdir -p homebrew-tap/Formula
# Run dry-run to get the rendered formula:
RELEASE_VERSION=<VERSION> ./scripts/release/components/homebrew.sh --dry-run \
  | grep -A999 "Formula content:"
# Paste the output into homebrew-tap/Formula/mockserver.rb, then:
cd homebrew-tap
git add Formula/mockserver.rb
git commit -m "mockserver <VERSION>"
git push
```
