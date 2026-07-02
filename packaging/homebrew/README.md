# Homebrew tap -- MockServer CLI

MockServer publishes a Homebrew tap so macOS and Linux users can install the
self-contained CLI bundle without managing a Java runtime separately:

```bash
brew install mock-server/tap/mockserver
```

## What gets installed

The tap formula installs the **self-contained jlink bundle** — a trimmed JVM bundled
directly into the archive alongside the launcher and the server JAR:

```
$(brew --prefix)/Cellar/mockserver/<version>/
  libexec/
    bin/
      mockserver        # launcher (shims the runtime path)
    lib/
      mockserver.jar    # shaded server JAR
    runtime/            # trimmed JVM — no system Java needed
```

Homebrew shims `libexec/bin/mockserver` as `mockserver` on `PATH`, so after
installation you can run:

```bash
mockserver -serverPort 1080
```

## Distinction from homebrew-core

Two Homebrew formulae exist for MockServer. They are complementary, not competing:

| | `mock-server/tap/mockserver` | `homebrew-core/mockserver` |
|---|---|---|
| **Install command** | `brew install mock-server/tap/mockserver` | `brew install mockserver` |
| **What it installs** | Self-contained jlink bundle (no JDK needed) | JAR + OpenJDK dependency |
| **Updated by** | MockServer release pipeline (this directory) | BrewTestBot from Maven Central |
| **Source** | GitHub Releases `mockserver-<version>-{darwin,linux}-{aarch64,x86_64}.tar.gz` | `mockserver-netty-<version>-brew-tar.tar` on Maven Central |

Use this tap formula if you prefer not to have Homebrew manage a JDK dependency,
or if you want the latest CLI release to appear as soon as the GitHub Release publishes.

## Supported platforms

| OS | Architecture |
|----|-------------|
| macOS | ARM64 (Apple Silicon) |
| macOS | x86_64 (Intel) |
| Linux | x86_64 |
| Linux | aarch64 |

## Publishing a new version

New versions are published automatically by the release pipeline — see
[release-component.md](release-component.md) for prerequisites and how to
activate the channel.
