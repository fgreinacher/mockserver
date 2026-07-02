package com.mockserver.jetbrains

import com.mockserver.jetbrains.MockServerBinary.Target
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the binary-bundle (no-Docker) launch logic — the pure platform →
 * artifact resolution, version pinning, checksum parsing, and launch-command
 * construction. No IDE, Docker, or network required.
 */
class StartBinaryActionTest {

    @Test
    fun `resolveTarget maps JVM os and arch to published bundle tokens`() {
        assertEquals(Target("darwin", "aarch64"), MockServerBinary.resolveTarget("Mac OS X", "aarch64"))
        assertEquals(Target("darwin", "x86_64"), MockServerBinary.resolveTarget("Mac OS X", "x86_64"))
        assertEquals(Target("linux", "x86_64"), MockServerBinary.resolveTarget("Linux", "amd64"))
        assertEquals(Target("linux", "aarch64"), MockServerBinary.resolveTarget("Linux", "aarch64"))
        assertEquals(Target("windows", "x86_64"), MockServerBinary.resolveTarget("Windows 11", "amd64"))
    }

    @Test
    fun `resolveTarget rejects unsupported OS, arch, and windows aarch64`() {
        assertFailsWith<IllegalStateException> { MockServerBinary.resolveTarget("AIX", "amd64") }
        assertFailsWith<IllegalStateException> { MockServerBinary.resolveTarget("Linux", "ppc64le") }
        // No windows/aarch64 bundle is published.
        val ex = assertFailsWith<IllegalStateException> { MockServerBinary.resolveTarget("Windows 11", "aarch64") }
        assertTrue(ex.message!!.contains("windows/aarch64"))
    }

    @Test
    fun `archive naming follows the published assets`() {
        val mac = Target("darwin", "aarch64")
        val win = Target("windows", "x86_64")
        assertEquals(".tar.gz", MockServerBinary.archiveExtension("darwin"))
        assertEquals(".zip", MockServerBinary.archiveExtension("windows"))
        assertEquals("mockserver-7.3.0-darwin-aarch64", MockServerBinary.bundleBaseName("7.3.0", mac))
        assertEquals("mockserver-7.3.0-darwin-aarch64.tar.gz", MockServerBinary.archiveFileName("7.3.0", mac))
        assertEquals("mockserver-7.3.0-windows-x86_64.zip", MockServerBinary.archiveFileName("7.3.0", win))
    }

    @Test
    fun `download and checksum urls point at the GitHub release asset`() {
        val t = Target("linux", "x86_64")
        assertEquals(
            "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-7.3.0/mockserver-7.3.0-linux-x86_64.tar.gz",
            MockServerBinary.downloadUrl("7.3.0", t)
        )
        assertEquals(MockServerBinary.downloadUrl("7.3.0", t) + ".sha256", MockServerBinary.checksumUrl("7.3.0", t))
    }

    @Test
    fun `launcher relative path is bin mockserver (bat on windows)`() {
        assertEquals("bin${File.separator}mockserver", MockServerBinary.launcherRelativePath("linux"))
        assertEquals("bin${File.separator}mockserver", MockServerBinary.launcherRelativePath("darwin"))
        assertEquals("bin${File.separator}mockserver.bat", MockServerBinary.launcherRelativePath("windows"))
    }

    @Test
    fun `serverArgs uses the backward-compatible serverPort flag`() {
        assertEquals(listOf("-serverPort", "2080"), MockServerBinary.serverArgs(2080))
    }

    @Test
    fun `start command runs the launcher with the configured port`() {
        val cmd = MockServerBinary.startCommand("/opt/ms/bin/mockserver", 2080)
        assertEquals("/opt/ms/bin/mockserver", cmd.exePath)
        assertEquals(listOf("-serverPort", "2080"), cmd.parametersList.parameters)
    }

    @Test
    fun `parseSha256 extracts the digest from a sha256sum sidecar`() {
        val line = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08  mockserver-7.3.0-linux-x86_64.tar.gz\n"
        assertEquals("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", MockServerBinary.parseSha256(line))
        assertFailsWith<IllegalStateException> { MockServerBinary.parseSha256("not a checksum") }
    }

    @Test
    fun `configuredLauncherPath appends bin mockserver only for a directory`() {
        assertEquals(
            "/opt/ms${File.separator}bin${File.separator}mockserver",
            MockServerBinary.configuredLauncherPath("/opt/ms", "linux", true)
        )
        assertEquals(
            "/opt/ms/bin/mockserver",
            MockServerBinary.configuredLauncherPath("/opt/ms/bin/mockserver", "linux", false)
        )
    }

    @Test
    fun `cachedLauncher nests under the base bundle dir`() {
        val cache = File("/store")
        val t = Target("darwin", "aarch64")
        assertEquals(
            File(File(cache, "mockserver-7.3.0-darwin-aarch64"), "bin${File.separator}mockserver"),
            MockServerBinary.cachedLauncher(cache, "7.3.0", t)
        )
    }

    @Test
    fun `resolveConfiguredLauncher returns null when the launcher is absent`() {
        assertEquals(null, MockServerBinary.resolveConfiguredLauncher("/nonexistent/bundle", "linux"))
    }

    @Test
    fun `checksumAbortReason is fail-closed for verified, mismatch, and missing-sidecar`() {
        val digest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        // sidecar present + digests match → proceed (null)
        assertEquals(null, MockServerBinary.checksumAbortReason(digest, digest, false))
        // match is case-insensitive
        assertEquals(null, MockServerBinary.checksumAbortReason(digest.uppercase(), digest, false))
        // sidecar present + mismatch → abort even with consent flag set
        assertNotNull(MockServerBinary.checksumAbortReason(digest, "deadbeef", true))
        // sidecar absent + NO consent → abort (fail-closed)
        val noSidecar = MockServerBinary.checksumAbortReason(null, null, false)
        assertNotNull(noSidecar)
        assertTrue(noSidecar!!.contains("could not be verified", ignoreCase = true))
        // sidecar absent + explicit consent → proceed (null)
        assertEquals(null, MockServerBinary.checksumAbortReason(null, null, true))
    }

    @Test
    fun `actions can be instantiated`() {
        assertNotNull(StartBinaryAction())
        assertNotNull(StopBinaryAction())
    }
}
