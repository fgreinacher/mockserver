package com.mockserver.jetbrains

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessHandlerFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.ShutDownTracker
import com.intellij.util.io.Decompressor
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Duration

/**
 * Binary-bundle (no-Docker) launch logic for corporate machines without Docker.
 *
 * MockServer publishes a self-contained, JVM-less binary bundle per platform on
 * each GitHub release (a jlink-trimmed Java runtime + the shaded jar + a launcher
 * script — see scripts/build-binary-bundle.sh). This object resolves which bundle
 * the current platform needs, where to download it from, verifies its published
 * SHA-256 sidecar, unpacks it, and runs its launcher — mirroring [MockServerDocker]
 * so the command is built from settings in exactly one place.
 *
 * Release artifact contract (confirmed against the published releases):
 * ```
 *   Release tag:   mockserver-<version>
 *   Asset (POSIX): mockserver-<version>-<os>-<arch>.tar.gz   (+ .sha256)
 *   Asset (win):   mockserver-<version>-windows-x86_64.zip   (+ .sha256)
 *   os   ∈ { linux, darwin, windows };  arch ∈ { x86_64, aarch64 }  (windows: x86_64 only)
 * ```
 * The unpacked tree is `mockserver-<version>-<os>-<arch>/{runtime,lib,bin}` with the
 * launcher at `bin/mockserver` (`bin/mockserver.bat` on windows).
 *
 * The pure resolution helpers reference only the JDK so they unit-test without a
 * running IDE; the process lifecycle and download use the IntelliJ platform.
 */
object MockServerBinary {

    /** GitHub repo (owner/name) whose releases carry the binary bundle assets. */
    const val RELEASE_REPO = "mock-server/mockserver-monorepo"

    data class Target(val os: String, val arch: String)

    // ---------------------------------------------------------------------
    // Pure resolution logic (JDK-only) — unit-testable without the IDE.
    // ---------------------------------------------------------------------

    /**
     * Map JVM `os.name` / `os.arch` to the bundle os/arch used in the published
     * asset names. Throws [IllegalStateException] for any platform/arch with no
     * published bundle so the caller can surface it verbatim.
     */
    fun resolveTarget(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): Target {
        val n = osName.lowercase()
        val os = when {
            n.contains("win") -> "windows"
            n.contains("mac") || n.contains("darwin") -> "darwin"
            n.contains("nux") || n.contains("nix") -> "linux"
            else -> error("No MockServer binary bundle is published for OS '$osName'. Supported: Linux, macOS, Windows.")
        }
        val arch = when (osArch.lowercase()) {
            "x86_64", "amd64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> error("No MockServer binary bundle is published for architecture '$osArch'. Supported: x86_64 (amd64), aarch64 (arm64).")
        }
        // Only windows/x86_64 is published (see scripts/build-all-bundles.sh targets).
        check(!(os == "windows" && arch != "x86_64")) {
            "No MockServer binary bundle is published for windows/$arch — only windows/x86_64."
        }
        return Target(os, arch)
    }

    /** Archive extension for the target OS: `.zip` on windows, else `.tar.gz`. */
    fun archiveExtension(os: String): String = if (os == "windows") ".zip" else ".tar.gz"

    /** Bundle base name (also the unpacked top-level dir), e.g. `mockserver-7.3.0-darwin-aarch64`. */
    fun bundleBaseName(version: String, target: Target): String =
        "mockserver-$version-${target.os}-${target.arch}"

    /** Release asset file name, e.g. `mockserver-7.3.0-darwin-aarch64.tar.gz`. */
    fun archiveFileName(version: String, target: Target): String =
        bundleBaseName(version, target) + archiveExtension(target.os)

    /** Full GitHub release download URL for the bundle archive. */
    fun downloadUrl(version: String, target: Target): String =
        "https://github.com/$RELEASE_REPO/releases/download/mockserver-$version/${archiveFileName(version, target)}"

    /** Full GitHub release download URL for the bundle's `.sha256` sidecar. */
    fun checksumUrl(version: String, target: Target): String = downloadUrl(version, target) + ".sha256"

    /** Launcher path relative to the unpacked bundle root (`bin\mockserver.bat` on windows). */
    fun launcherRelativePath(os: String): String =
        if (os == "windows") "bin${File.separator}mockserver.bat" else "bin${File.separator}mockserver"

    /**
     * CLI arguments to start MockServer on [port] — the legacy `-serverPort` flag,
     * still accepted by the CLI (org.mockserver.cli.Main) and needing no subcommand.
     */
    fun serverArgs(port: Int): List<String> = listOf("-serverPort", port.toString())

    /**
     * Parse the hex digest out of a `.sha256` sidecar (`sha256sum` / `shasum -a 256`
     * output: `<64-hex>␠␠<filename>`). Returns the lower-cased digest; throws if
     * no 64-char hex digest is present.
     */
    fun parseSha256(content: String): String {
        val match = Regex("\\b([0-9a-fA-F]{64})\\b").find(content.trim())
            ?: error("Could not find a SHA-256 digest in the checksum file")
        return match.groupValues[1].lowercase()
    }

    /**
     * Pure form of the configured-path resolution: appends the launcher when the
     * path is a directory, else returns it verbatim. [isDirectory] is passed in so
     * this stays testable; [resolveConfiguredLauncher] does the actual stat.
     */
    fun configuredLauncherPath(binaryPath: String, os: String, isDirectory: Boolean): String {
        val trimmed = binaryPath.trim()
        return if (isDirectory) trimmed + File.separator + launcherRelativePath(os) else trimmed
    }

    /**
     * Fail-CLOSED checksum decision, mirroring the VS Code extension. Returns an
     * abort reason (message) when the install must NOT proceed, or `null` when it may:
     *  - sidecar present + digests match  → proceed (`null`)
     *  - sidecar present + digests differ → abort (tampered/corrupt download)
     *  - sidecar absent + no user consent → abort (cannot verify — the safe default,
     *    important behind TLS-inspection proxies where the sidecar fetch fails but the
     *    archive succeeds)
     *  - sidecar absent + explicit consent → proceed (`null`)
     *
     * [actual] is only consulted when [expected] is non-null.
     */
    fun checksumAbortReason(expected: String?, actual: String?, consentWithoutChecksum: Boolean): String? {
        if (expected != null) {
            return if (actual != null && actual.equals(expected, ignoreCase = true)) {
                null
            } else {
                "Checksum mismatch (expected $expected, got ${actual ?: "?"})."
            }
        }
        return if (consentWithoutChecksum) {
            null
        } else {
            "Checksum could not be verified: the .sha256 sidecar could not be fetched " +
                "(common behind a TLS-inspection proxy). Install aborted for safety."
        }
    }

    // ---------------------------------------------------------------------
    // File-system resolution.
    // ---------------------------------------------------------------------

    /** Directory a downloaded/unpacked bundle lives in, under [cacheDir]. */
    fun cachedBundleDir(cacheDir: File, version: String, target: Target): File =
        File(cacheDir, bundleBaseName(version, target))

    /** Full path to the launcher inside the cached, unpacked bundle. */
    fun cachedLauncher(cacheDir: File, version: String, target: Target): File =
        File(cachedBundleDir(cacheDir, version, target), launcherRelativePath(target.os))

    /**
     * Resolve a user-supplied `binaryPath` (launcher file or unpacked bundle dir)
     * to the launcher file. Returns `null` when the resolved launcher does not
     * exist so the caller can produce a clear error.
     */
    fun resolveConfiguredLauncher(binaryPath: String, os: String): File? {
        val trimmed = binaryPath.trim()
        val asFile = File(trimmed)
        val launcher = File(configuredLauncherPath(trimmed, os, asFile.isDirectory))
        return if (launcher.isFile) launcher else null
    }

    // ---------------------------------------------------------------------
    // Command building + process lifecycle.
    // ---------------------------------------------------------------------

    /** Build the launcher command line (`<launcher> -serverPort <port>`). */
    fun startCommand(launcherPath: String, port: Int): GeneralCommandLine =
        GeneralCommandLine(buildList { add(launcherPath); addAll(serverArgs(port)) })

    @Volatile
    private var handler: ProcessHandler? = null

    // Registered exactly once (across all starts) so IDE shutdown terminates a
    // bundle-launched MockServer instead of orphaning it (which would hold the port
    // and, since [handler] is lost on restart, leave it unstoppable from the IDE).
    private val shutdownHookRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True when a bundle-launched MockServer is running in this IDE session. */
    fun isRunning(): Boolean = handler?.let { !it.isProcessTerminated } ?: false

    /**
     * Start the launcher in the background, tracking the handle for [stop]. Throws
     * if a bundle process is already running. Registers an IDE-shutdown cleanup so
     * the process is destroyed when the IDE closes (mirrors the VS Code extension's
     * `deactivate()` cleanup) — otherwise the JVM would outlive the IDE, keep the
     * port bound, and be unstoppable in-IDE after a restart.
     */
    fun start(launcherPath: String, port: Int) {
        check(!isRunning()) { "A MockServer binary process is already running in this IDE." }
        registerShutdownCleanup()
        val h = ProcessHandlerFactory.getInstance().createProcessHandler(startCommand(launcherPath, port))
        handler = h
        h.startNotify()
    }

    /** Terminate the tracked bundle process, if any. */
    fun stop() {
        handler?.destroyProcess()
        handler = null
    }

    private fun registerShutdownCleanup() {
        if (shutdownHookRegistered.compareAndSet(false, true)) {
            ShutDownTracker.getInstance().registerShutdownTask {
                handler?.let { if (!it.isProcessTerminated) it.destroyProcess() }
            }
        }
    }

    /**
     * Locate the launcher to run: an explicit [binaryPath], else the cached
     * download in [cacheDir], else a fresh download (verified + unpacked). Blocks on
     * network I/O — call from a background thread. Reports coarse progress on
     * [indicator]. Throws with a clear message on any failure. [consentWithoutChecksum]
     * is consulted only if the checksum sidecar cannot be fetched (see [downloadAndExtract]).
     */
    fun locateOrDownloadLauncher(
        binaryPath: String,
        version: String,
        cacheDir: File,
        target: Target = resolveTarget(),
        indicator: ProgressIndicator? = null,
        consentWithoutChecksum: () -> Boolean = { false },
    ): File {
        if (binaryPath.isNotBlank()) {
            return resolveConfiguredLauncher(binaryPath, target.os)
                ?: error(
                    "Binary bundle path is set but no launcher was found. Point it at the bundle's " +
                        "${launcherRelativePath(target.os)} launcher or the unpacked bundle directory."
                )
        }
        val cached = cachedLauncher(cacheDir, version, target)
        if (cached.isFile) return cached
        downloadAndExtract(version, cacheDir, target, indicator, consentWithoutChecksum)
        check(cached.isFile) { "Bundle downloaded but launcher not found at $cached." }
        return cached
    }

    /**
     * Download the bundle archive from the GitHub release, verify its published
     * SHA-256 sidecar, and unpack it into [cacheDir].
     *
     * Checksum verification is FAIL-CLOSED (see [checksumAbortReason]): a mismatch
     * always aborts, and a sidecar that cannot be fetched aborts too UNLESS
     * [consentWithoutChecksum] returns true (an explicit second user consent). The
     * default `{ false }` keeps non-interactive/test callers safe. Extraction uses
     * IntelliJ's [Decompressor], which guards against zip-slip / tar-slip entries.
     */
    fun downloadAndExtract(
        version: String,
        cacheDir: File,
        target: Target,
        indicator: ProgressIndicator? = null,
        consentWithoutChecksum: () -> Boolean = { false },
    ) {
        Files.createDirectories(cacheDir.toPath())
        val archive = File(cacheDir, archiveFileName(version, target))
        indicator?.text = "Downloading ${archive.name}"
        download(downloadUrl(version, target), archive)

        indicator?.text = "Verifying checksum"
        val expected = runCatching { parseSha256(fetchText(checksumUrl(version, target))) }.getOrNull()
        val actual = if (expected != null) sha256(archive) else null
        // Only ask for consent when the sidecar is genuinely missing (expected == null).
        val consent = if (expected == null) consentWithoutChecksum() else false
        checksumAbortReason(expected, actual, consent)?.let { reason ->
            archive.delete()
            error("${archive.name}: $reason")
        }

        indicator?.text = "Extracting ${archive.name}"
        // Clear any stale partial extraction, then unpack into cacheDir (the archive
        // contains the mockserver-<version>-<os>-<arch>/ top-level directory).
        cachedBundleDir(cacheDir, version, target).deleteRecursively()
        if (target.os == "windows") {
            Decompressor.Zip(archive).extract(cacheDir)
        } else {
            Decompressor.Tar(archive).extract(cacheDir)
            // Decompressor may not restore the exec bit; re-assert it on the launcher.
            cachedLauncher(cacheDir, version, target).setExecutable(true, false)
        }
        archive.delete()
    }

    private fun newHttpClient(): HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // GitHub release assets 302 to a CDN
            .connectTimeout(Duration.ofSeconds(30))
            .build()

    private fun download(url: String, dest: File) {
        val response = newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofFile(dest.toPath()),
        )
        check(response.statusCode() in 200..299) { "Download failed: HTTP ${response.statusCode()} for $url" }
    }

    private fun fetchText(url: String): String {
        val response = newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} for $url" }
        return response.body()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
