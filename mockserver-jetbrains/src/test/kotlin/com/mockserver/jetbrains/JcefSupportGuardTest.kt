package com.mockserver.jetbrains

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Source guard for the one regression the IntelliJ Plugin Verifier cannot see.
 *
 * In 8.0.0 the dashboard and LLM tool windows probed JCEF with a direct
 * `JBCefApp.isSupported()` call. On IntelliJ IDEA 2026.2 (build 262) the class
 * `com.intellij.ui.jcef.JBCefApp` is no longer in this plugin's classloader graph
 * (it lives in the optional module `intellij.platform.ui.jcef`, and the plugin
 * declares only `com.intellij.modules.platform` + `com.intellij.modules.json`), so
 * touching the class threw `NoClassDefFoundError` straight onto the EDT and the tool
 * window failed to open.
 *
 * `verifyPlugin` did NOT catch this and cannot: it resolves the plugin against the
 * IDE's full distribution, where `JBCefApp` is present, so it reports the plugin
 * "Compatible" against build 262 even though the runtime PluginClassLoader cannot
 * load the class. (Verified empirically: reverting the [JcefSupport] fix and running
 * `./gradlew verifyPlugin` still reports BUILD SUCCESSFUL against IU-262.)
 *
 * The defence is therefore a code guard, not a verifier failure level:
 * [JcefSupport.isAvailable] wraps the probe in `catch (LinkageError)`, and this test
 * keeps it the ONLY entry point — the whole point is defeated the moment any other
 * class loads `JBCefApp` directly, because that call is the unguarded one that throws.
 * `JBCefBrowser` is intentionally not guarded here: it is only ever instantiated
 * inside an `if (JcefSupport.isAvailable())` branch, so it is never loaded when JCEF
 * is absent.
 */
class JcefSupportGuardTest {

    @Test
    fun `JBCefApp is referenced only from the LinkageError-guarded JcefSupport`() {
        val sourceRoot = locateMainKotlinSourceRoot()
        val offenders = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "JcefSupport.kt" }
            .filter { file -> file.readLines().any { line -> line.contains("JBCefApp") } }
            .map { it.name }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "com.intellij.ui.jcef.JBCefApp must only be referenced from JcefSupport, which wraps " +
                    "the probe in catch (LinkageError) so a JCEF-less IDE (e.g. IntelliJ IDEA 2026.2) " +
                    "degrades to the external browser instead of throwing NoClassDefFoundError. " +
                    "Route the check through JcefSupport.isAvailable() in: ${offenders.joinToString(", ")}"
            )
        }
    }

    @Test
    fun `JcefSupport actually guards the probe with a LinkageError catch`() {
        val jcefSupport = locateMainKotlinSourceRoot().resolve("com/mockserver/jetbrains/JcefSupport.kt")
        assertTrue(jcefSupport.isFile, "JcefSupport.kt not found at ${jcefSupport.path}")
        val text = jcefSupport.readText()
        assertTrue(text.contains("JBCefApp"), "JcefSupport is expected to be the single JBCefApp entry point")
        assertTrue(
            text.contains("catch (e: LinkageError)") || text.contains("catch (e : LinkageError)"),
            "JcefSupport must catch LinkageError — NoClassDefFoundError is an Error, not an Exception, " +
                "and is what a missing JCEF module throws when JBCefApp is first loaded"
        )
    }

    /**
     * Resolve `src/main/kotlin` without assuming the test's working directory: walk up
     * from `user.dir` until the module root (the directory containing `src/main/kotlin`)
     * is found. Gradle runs tests with the module dir as the working directory, but this
     * stays correct if that ever changes.
     */
    private fun locateMainKotlinSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            val candidate = File(dir, "src/main/kotlin")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("Could not locate src/main/kotlin from working directory ${System.getProperty("user.dir")}")
    }
}
