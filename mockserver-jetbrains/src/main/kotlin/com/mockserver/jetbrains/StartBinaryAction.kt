package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.io.File

/**
 * Action that starts MockServer from a self-contained binary bundle — no Docker
 * required — for corporate machines without a Docker daemon. Mirrors
 * [StartDockerAction]'s lifecycle (a [Task.Backgroundable] doing the blocking work,
 * results posted back on the EDT via [runOnEdt]) but launches the bundle launcher
 * instead of `docker run`.
 *
 * The bundle is taken from the configured [MockServerSettings.effectiveBinaryPath]
 * when set, otherwise downloaded once — after an explicit user confirmation — into
 * the IDE cache directory and reused. The download is checksum-verified fail-closed:
 * if the `.sha256` sidecar cannot be fetched, a second confirmation is required
 * before installing unverified. The launched process is registered for IDE-shutdown
 * cleanup by [MockServerBinary.start]. See [MockServerBinary].
 */
class StartBinaryAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = MockServerSettings.getInstance()
        val port = settings.effectivePort()
        val binaryPath = settings.effectiveBinaryPath()
        val version = settings.effectiveBinaryVersion()

        if (MockServerBinary.isRunning()) {
            MockServerNotifier.notify(
                project,
                "MockServer (binary) is already running on port $port. Stop it first to restart.",
                NotificationType.INFORMATION
            )
            return
        }

        object : Task.Backgroundable(project, "Starting MockServer (binary)", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val target = MockServerBinary.resolveTarget()
                    val cacheDir = File(PathManager.getSystemPath(), "mockserver-bundles")

                    // First consent: only when a download is actually needed (no configured
                    // path and nothing cached), mirroring the VS Code modal prompt.
                    val needsDownload = binaryPath.isBlank() &&
                        !MockServerBinary.cachedLauncher(cacheDir, version, target).isFile
                    if (needsDownload) {
                        val archiveName = MockServerBinary.archiveFileName(version, target)
                        val proceed = confirmOnEdt(
                            project,
                            "Download MockServer Bundle",
                            "No MockServer binary bundle found for ${target.os}/${target.arch}.\n" +
                                "Download $archiveName from GitHub (no Docker required)?"
                        )
                        if (!proceed) {
                            runOnEdt(project) {
                                MockServerNotifier.notify(
                                    project,
                                    "MockServer binary download cancelled.",
                                    NotificationType.INFORMATION
                                )
                            }
                            return
                        }
                    }

                    val launcher = MockServerBinary.locateOrDownloadLauncher(
                        binaryPath, version, cacheDir, target, indicator,
                        consentWithoutChecksum = {
                            // Second consent: sidecar could not be fetched (common behind a
                            // TLS-inspection proxy). Require explicit confirmation to install unverified.
                            confirmOnEdt(
                                project,
                                "Checksum Unavailable",
                                "Could not verify the checksum of ${MockServerBinary.archiveFileName(version, target)}: " +
                                    "the .sha256 sidecar could not be fetched (common behind a TLS-inspection proxy).\n\n" +
                                    "Install without checksum verification?"
                            )
                        }
                    )
                    MockServerBinary.start(launcher.absolutePath, port)
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Started MockServer (binary) on port $port. It should be reachable shortly.",
                            NotificationType.INFORMATION
                        )
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) {
                        MockServerNotifier.notify(
                            project,
                            "Failed to start MockServer (binary): ${ex.message}",
                            NotificationType.ERROR
                        )
                    }
                }
            }
        }.queue()
    }

    /**
     * Show a modal Yes/No dialog on the EDT and block for the answer. Called from the
     * background task thread, so it uses
     * [com.intellij.openapi.application.Application.invokeAndWait].
     */
    private fun confirmOnEdt(project: Project, title: String, message: String): Boolean {
        var confirmed = false
        ApplicationManager.getApplication().invokeAndWait {
            confirmed = Messages.showYesNoDialog(project, message, title, Messages.getQuestionIcon()) == Messages.YES
        }
        return confirmed
    }
}
