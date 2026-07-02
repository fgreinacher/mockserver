package com.mockserver.jetbrains

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

/**
 * Tests a compiled WebAssembly (`.wasm`) custom-rule module against a sample request via
 * `POST /mockserver/wasm/test`, without uploading it or creating an expectation. The user
 * picks a `.wasm` file and supplies a sample method, path and optional body; MockServer runs
 * the module and reports whether it matched. Handy for quick local iteration on a rule.
 *
 * The file chooser, input dialogs and notifications run on the EDT; the HTTP call runs on a
 * background thread. Matching is fail-closed server-side — an invalid module reports no match
 * rather than an error; a 403 means WASM support is disabled and surfaces verbatim.
 */
class TestWasmModuleAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("wasm")
            .withTitle("Select WASM Module")
            .withDescription("Choose a compiled .wasm custom-rule module to test against a sample request")
        val vFile = FileChooser.chooseFile(descriptor, project, null) ?: return

        val method = Messages.showInputDialog(
            project,
            "Sample request method to test the WASM rule against:",
            "Test WASM Rule",
            null,
            "POST",
            null
        ) ?: return

        val path = Messages.showInputDialog(
            project,
            "Sample request path to test the WASM rule against:",
            "Test WASM Rule",
            null,
            "/",
            null
        ) ?: return

        val body = Messages.showInputDialog(
            project,
            "Sample request body (optional):",
            "Test WASM Rule",
            null,
            "",
            null
        ) ?: return

        val bytes = try {
            vFile.contentsToByteArray()
        } catch (ex: Exception) {
            MockServerNotifier.notify(project, "Failed to read ${vFile.name}: ${ex.message}", NotificationType.ERROR)
            return
        }

        val spec = MockServerRestClient.WasmTestSpec(
            method = method.trim(),
            path = path.trim(),
            body = body.ifBlank { null }
        )
        val baseUrl = MockServerRestClient.buildBaseUrl(MockServerSettings.getInstance().effectivePort())

        object : Task.Backgroundable(project, "Testing WASM rule against MockServer", true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = MockServerRestClient.send(
                        MockServerRestClient.buildWasmTestRequest(baseUrl, bytes, spec)
                    )
                    if (result.ok) {
                        val matched = MockServerRestClient.parseWasmTestMatched(result.body)
                        runOnEdt(project) {
                            MockServerNotifier.notify(
                                project,
                                if (matched) {
                                    "WASM rule matched ${spec.method} ${spec.path}."
                                } else {
                                    "WASM rule did not match ${spec.method} ${spec.path}."
                                },
                                if (matched) NotificationType.INFORMATION else NotificationType.WARNING
                            )
                        }
                    } else {
                        runOnEdt(project) { MockServerNotifier.notify(project, "MockServer returned ${result.status}: ${result.body}", NotificationType.ERROR) }
                    }
                } catch (ex: Exception) {
                    runOnEdt(project) { MockServerNotifier.notify(project, "Failed to reach MockServer at $baseUrl: ${ex.message}", NotificationType.ERROR) }
                }
            }
        }.queue()
    }
}
