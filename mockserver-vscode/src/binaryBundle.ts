// Binary-bundle (no-Docker) launch support for corporate machines without Docker.
//
// MockServer publishes a self-contained, JVM-less binary bundle per platform on
// each GitHub release (a jlink-trimmed Java runtime + the shaded jar + a launcher
// script — see scripts/build-binary-bundle.sh). This module holds the PURE logic
// for resolving which bundle to use for the current platform, where to download it
// from, how to verify its checksum, and how to invoke its launcher. It is
// deliberately `vscode`-free so every function here is unit-testable on the plain
// Node test classpath; the command wiring (progress UI, spawning, output channel)
// lives in extension.ts.
//
// Release artifact contract (confirmed against the published releases):
//   Release tag:   mockserver-<version>
//   Asset (POSIX): mockserver-<version>-<os>-<arch>.tar.gz   (+ .sha256)
//   Asset (win):   mockserver-<version>-windows-x86_64.zip   (+ .sha256)
//   os   ∈ { linux, darwin, windows }
//   arch ∈ { x86_64, aarch64 }   (windows: x86_64 only)
// The unpacked tree is  mockserver-<version>-<os>-<arch>/{runtime,lib,bin}
// with the launcher at   bin/mockserver   (bin/mockserver.bat on windows).

import * as path from "path";
import * as fs from "fs";
import * as crypto from "crypto";

/** GitHub repo (owner/name) whose releases carry the binary bundle assets. */
export const BINARY_RELEASE_REPO = "mock-server/mockserver-monorepo";

export type BundleOs = "linux" | "darwin" | "windows";
export type BundleArch = "x86_64" | "aarch64";

export interface BundleTarget {
    os: BundleOs;
    arch: BundleArch;
}

/**
 * Map a Node `process.platform` / `process.arch` pair to the bundle os/arch used
 * in the published asset names. Throws a clear error for any platform/arch that
 * has no published bundle so the caller can surface it verbatim.
 */
export function resolveTarget(platform: NodeJS.Platform, arch: string): BundleTarget {
    let os: BundleOs;
    switch (platform) {
        case "linux":
            os = "linux";
            break;
        case "darwin":
            os = "darwin";
            break;
        case "win32":
            os = "windows";
            break;
        default:
            throw new Error(
                `No MockServer binary bundle is published for platform '${platform}'. ` +
                    `Supported: linux, macOS (darwin), Windows.`
            );
    }
    let bundleArch: BundleArch;
    switch (arch) {
        case "x64":
            bundleArch = "x86_64";
            break;
        case "arm64":
            bundleArch = "aarch64";
            break;
        default:
            throw new Error(
                `No MockServer binary bundle is published for architecture '${arch}'. ` +
                    `Supported: x64 (x86_64), arm64 (aarch64).`
            );
    }
    // Only windows/x86_64 is published (see scripts/build-all-bundles.sh targets).
    if (os === "windows" && bundleArch !== "x86_64") {
        throw new Error(
            `No MockServer binary bundle is published for windows/${bundleArch} — only windows/x86_64.`
        );
    }
    return { os, arch: bundleArch };
}

/** Archive extension for the target OS: `.zip` on windows, else `.tar.gz`. */
export function archiveExtension(os: BundleOs): string {
    return os === "windows" ? ".zip" : ".tar.gz";
}

/** Bundle base name (also the unpacked top-level directory), e.g. `mockserver-7.3.0-darwin-aarch64`. */
export function bundleBaseName(version: string, target: BundleTarget): string {
    return `mockserver-${version}-${target.os}-${target.arch}`;
}

/** Release asset file name, e.g. `mockserver-7.3.0-darwin-aarch64.tar.gz`. */
export function archiveFileName(version: string, target: BundleTarget): string {
    return bundleBaseName(version, target) + archiveExtension(target.os);
}

/** Full GitHub release download URL for the bundle archive. */
export function downloadUrl(version: string, target: BundleTarget): string {
    return (
        `https://github.com/${BINARY_RELEASE_REPO}/releases/download/` +
        `mockserver-${version}/${archiveFileName(version, target)}`
    );
}

/** Full GitHub release download URL for the bundle's `.sha256` checksum sidecar. */
export function checksumUrl(version: string, target: BundleTarget): string {
    return downloadUrl(version, target) + ".sha256";
}

/**
 * Path to the bundled Java executable relative to the unpacked bundle root:
 * `runtime/bin/java` (`runtime\bin\java.exe` on windows). MockServer is launched
 * by running this Java executable directly (`java -jar mockserver.jar …`) rather
 * than the `bin/mockserver[.bat]` wrapper — that keeps every path out of any
 * shell interpreter (no `cmd.exe /c`), removing the command-injection surface.
 */
export function javaExecutableRelativePath(os: BundleOs): string {
    return os === "windows"
        ? path.join("runtime", "bin", "java.exe")
        : path.join("runtime", "bin", "java");
}

/** App-jar path relative to the unpacked bundle root: `lib/mockserver.jar`. */
export function jarRelativePath(): string {
    return path.join("lib", "mockserver.jar");
}

/** The bundled Java executable + app jar resolved from a bundle root directory. */
export interface BundleJava {
    /** Absolute path to the bundled Java executable (`…/runtime/bin/java[.exe]`). */
    javaExecutable: string;
    /** Absolute path to the shaded MockServer jar (`…/lib/mockserver.jar`). */
    jarPath: string;
}

/**
 * Resolve the bundled Java executable and app jar from an unpacked bundle ROOT
 * directory. The launch spawns `javaExecutable` directly with
 * `["-jar", jarPath, …serverArgs]` — no launcher script, no shell.
 */
export function bundleJavaAndJar(bundleRootDir: string, os: BundleOs): BundleJava {
    return {
        javaExecutable: path.join(bundleRootDir, javaExecutableRelativePath(os)),
        jarPath: path.join(bundleRootDir, jarRelativePath()),
    };
}

/**
 * CLI arguments to start MockServer on `port`. Uses the legacy `-serverPort`
 * flag, which the CLI still accepts for backward compatibility (see
 * org.mockserver.cli.Main) and which needs no positional subcommand.
 */
export function serverArgs(port: number): string[] {
    return ["-serverPort", String(port)];
}

/**
 * Parse the hex digest out of a `.sha256` sidecar. The files are written as
 * `sha256sum` / `shasum -a 256` output: `<64-hex>␠␠<filename>`. Returns the
 * lower-cased digest; throws if no 64-char hex digest is present.
 */
export function parseSha256(checksumFileContent: string): string {
    const match = checksumFileContent.trim().match(/\b([0-9a-fA-F]{64})\b/);
    if (!match) {
        throw new Error("Could not find a SHA-256 digest in the checksum file");
    }
    return match[1].toLowerCase();
}

/** Directory a downloaded/unpacked bundle lives in, under the extension's global storage. */
export function cachedBundleDir(globalStorageDir: string, version: string, target: BundleTarget): string {
    return path.join(globalStorageDir, "bundles", bundleBaseName(version, target));
}

/**
 * Resolve a user-supplied `mockserver.binaryPath` to the unpacked bundle ROOT
 * directory. The setting may point either at the unpacked bundle root directory
 * itself, or at the launcher executable inside it (`bin/mockserver[.bat]`), in
 * which case the root is the launcher's grandparent (`…/bin/mockserver` → `…`).
 * The caller derives the Java executable + jar from this root via
 * `bundleJavaAndJar`. `isDirectory` is passed in so this stays pure/testable;
 * the caller stats the path.
 */
export function resolveConfiguredBundleRoot(binaryPath: string, isDirectory: boolean): string {
    const trimmed = binaryPath.trim();
    if (isDirectory) {
        return trimmed;
    }
    // A launcher FILE (…/bin/mockserver[.bat]): the bundle root is its grandparent.
    return path.dirname(path.dirname(trimmed));
}

/**
 * Split a `MOCKSERVER_JAVA_OPTS` value into individual JVM arguments, matching
 * the bundle launcher's unquoted `${MOCKSERVER_JAVA_OPTS:-}` / `%MOCKSERVER_JAVA_OPTS%`
 * shell expansion — i.e. whitespace word-splitting with no quote handling.
 * Returns `[]` when the variable is unset or blank. This preserves the launcher's
 * documented "override JVM options via MOCKSERVER_JAVA_OPTS" behaviour now that we
 * spawn `java` directly instead of the script (java itself does not read that var).
 */
export function parseJavaOpts(javaOpts: string | undefined): string[] {
    if (!javaOpts) {
        return [];
    }
    return javaOpts.trim().split(/\s+/).filter((s) => s.length > 0);
}

/**
 * Validate a bundle version string before it is interpolated into download URLs
 * and filesystem paths. Matches the same character class the release build enforces
 * (`scripts/build-binary-bundle.sh`): letters, digits, dot, underscore, hyphen.
 */
export function isValidVersion(version: string): boolean {
    return /^[A-Za-z0-9._-]+$/.test(version);
}

/**
 * Fail-CLOSED checksum decision, shared by both editors. Returns an abort reason
 * (a message) when the install must NOT proceed, or `undefined` when it may:
 *  - sidecar present + digests match  → proceed
 *  - sidecar present + digests differ → abort (tampered/corrupt download)
 *  - sidecar absent + no user consent → abort (cannot verify — the safe default,
 *    important behind TLS-inspection proxies where the sidecar fetch fails but the
 *    archive succeeds)
 *  - sidecar absent + explicit consent → proceed (user accepted the risk)
 *
 * `actualDigest` is only consulted when `expectedDigest` is present.
 */
export function checksumAbortReason(
    expectedDigest: string | undefined,
    actualDigest: string | undefined,
    consentWithoutChecksum: boolean
): string | undefined {
    if (expectedDigest !== undefined) {
        if (actualDigest !== undefined && actualDigest.toLowerCase() === expectedDigest.toLowerCase()) {
            return undefined;
        }
        return `Checksum mismatch (expected ${expectedDigest}, got ${actualDigest ?? "?"}).`;
    }
    if (consentWithoutChecksum) {
        return undefined;
    }
    return (
        "Checksum could not be verified: the .sha256 sidecar could not be fetched " +
        "(common behind a TLS-inspection proxy). Install aborted for safety."
    );
}

/**
 * Zip-slip / tar-slip guard: return the first archive entry that would escape the
 * extraction directory (an absolute path or a `..`-escaping component), or
 * `undefined` when every entry is safe. Pure, so it is unit-testable; the caller
 * lists the archive entries (`tar -tzf`) and refuses extraction when this is set.
 */
export function firstUnsafeArchiveEntry(entries: string[]): string | undefined {
    for (const raw of entries) {
        const entry = raw.trim();
        if (entry === "") {
            continue;
        }
        // Absolute (POSIX or Windows drive/UNC) paths.
        if (entry.startsWith("/") || entry.startsWith("\\") || /^[A-Za-z]:[\\/]/.test(entry)) {
            return entry;
        }
        // Any `..` path component, in either separator style.
        const parts = entry.split(/[\\/]/);
        if (parts.some((p) => p === "..")) {
            return entry;
        }
    }
    return undefined;
}

/** Compute the SHA-256 of a file, streaming so large archives don't buffer in memory. */
export function sha256File(filePath: string): Promise<string> {
    return new Promise((resolve, reject) => {
        const hash = crypto.createHash("sha256");
        const stream = fs.createReadStream(filePath);
        stream.on("error", reject);
        stream.on("data", (chunk) => hash.update(chunk));
        stream.on("end", () => resolve(hash.digest("hex").toLowerCase()));
    });
}
