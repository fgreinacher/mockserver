package com.mockserver.jetbrains

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings panel under **Settings | Tools | MockServer** for the Docker image,
 * container name, port, and the trace-backend URL template used by the
 * **View Trace in Backend** action.
 */
class MockServerConfigurable : Configurable {

    private val imageField = JBTextField()
    private val containerField = JBTextField()
    private val portField = JBTextField()
    private val traceUrlField = JBTextField()
    private val binaryPathField = JBTextField()

    override fun getDisplayName(): String = "MockServer"

    override fun createComponent(): JComponent {
        imageField.emptyText.text = "mockserver/mockserver:<plugin version>"
        traceUrlField.emptyText.text = "http://localhost:16686/trace/{traceId}"
        binaryPathField.emptyText.text = "download on demand (no Docker) — or /path/to/bundle"
        val panel: JPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Docker image:", imageField)
            .addLabeledComponent("Container name:", containerField)
            .addLabeledComponent("Host port:", portField)
            .addLabeledComponent("Trace backend URL ({traceId}):", traceUrlField)
            .addLabeledComponent("Binary bundle path (no Docker):", binaryPathField)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel
    }

    private fun state() = MockServerSettings.getInstance().state

    override fun isModified(): Boolean {
        val s = state()
        return imageField.text != s.dockerImage ||
            containerField.text != s.containerName ||
            portField.text != s.port.toString() ||
            traceUrlField.text != s.traceUrlTemplate ||
            binaryPathField.text != s.binaryPath
    }

    override fun apply() {
        // Mutates the live state object returned by getState() — valid for a
        // PersistentStateComponent (the platform serializes that same object).
        // There are no state-change listeners; add a dedicated setter if that changes.
        val s = state()
        s.dockerImage = imageField.text.trim()
        s.containerName = containerField.text.trim().ifBlank { MockServerSettings.DEFAULT_CONTAINER_NAME }
        s.port = portField.text.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: MockServerSettings.DEFAULT_PORT
        s.traceUrlTemplate = traceUrlField.text.trim()
        s.binaryPath = binaryPathField.text.trim()
        reset() // reflect normalised values back into the fields
    }

    override fun reset() {
        val s = state()
        imageField.text = s.dockerImage
        containerField.text = s.containerName
        portField.text = s.port.toString()
        traceUrlField.text = s.traceUrlTemplate
        binaryPathField.text = s.binaryPath
    }
}
