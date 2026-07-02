package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action that stops the MockServer process started from a binary bundle by
 * [StartBinaryAction]. Complements the start action's lifecycle; the Docker path
 * has no in-IDE stop (the container is `--rm` and managed via Docker), so this
 * stop applies only to the bundle-launched process tracked by [MockServerBinary].
 */
class StopBinaryAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (!MockServerBinary.isRunning()) {
            MockServerNotifier.notify(
                project,
                "No MockServer binary process is running in this IDE.",
                NotificationType.WARNING
            )
            return
        }
        MockServerBinary.stop()
        MockServerNotifier.notify(
            project,
            "Stopped MockServer (binary).",
            NotificationType.INFORMATION
        )
    }
}
