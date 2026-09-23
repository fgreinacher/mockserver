package com.mockserver.jetbrains

import com.intellij.openapi.diagnostic.Logger

/**
 * Whether the embedded Chromium browser (JCEF) can actually be used in this IDE.
 *
 * WHY THIS IS NOT JUST `JBCefApp.isSupported()`, which is what it replaced. That call answers
 * "is JCEF supported here" only once `JBCefApp` has loaded — and the class is not always
 * reachable. This plugin declares `com.intellij.modules.platform` and
 * `com.intellij.modules.json` and nothing more, so `com.intellij.ui.jcef` is only on the
 * classpath when the running IDE happens to put it in the plugin's classloader graph. On
 * IntelliJ IDEA 2026.2 it does not, and opening the MockServer Dashboard tool window threw
 * `NoClassDefFoundError: com/intellij/ui/jcef/JBCefApp` straight onto the EDT, so the tool
 * window failed to open at all.
 *
 * The bitter part is that both call sites already had a fallback for JCEF being unavailable —
 * a panel offering the external browser. Reaching that fallback required loading `JBCefApp` to
 * ask whether it was supported, so the one case the fallback existed for was the one case it
 * could not be reached in.
 *
 * `LinkageError` is therefore caught as well as `Exception`: `NoClassDefFoundError` is an
 * *Error*, not an exception, and is what a missing class throws at first use. They are caught
 * separately rather than as `Throwable` so that genuinely fatal conditions — an
 * `OutOfMemoryError`, a thread being killed — still propagate.
 *
 * A hard `<depends>` on a JCEF module would be the wrong fix: it would stop the plugin loading
 * at all on IDEs without JCEF, turning a degraded dashboard into no plugin.
 */
internal object JcefSupport {

    private val LOG = Logger.getInstance(JcefSupport::class.java)

    /** True when JCEF is present AND usable; false when it is missing, disabled or unsupported. */
    fun isAvailable(): Boolean =
        try {
            com.intellij.ui.jcef.JBCefApp.isSupported()
        } catch (e: LinkageError) {
            // JCEF is not in this plugin's classloader graph at all.
            LOG.info("JCEF is unavailable in this IDE (${e.javaClass.simpleName}); using the external browser instead")
            false
        } catch (e: Exception) {
            LOG.info("JCEF availability check failed; using the external browser instead", e)
            false
        }
}
